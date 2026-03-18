package simpledb.optimizer;

import simpledb.ParsingException;
import simpledb.common.Database;
import simpledb.execution.*;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import java.util.*;

/**
 * The JoinOptimizer class is responsible for ordering a series of joins
 * optimally, and for selecting the best instantiation of a join for a given
 * logical plan.
 */
public class JoinOptimizer {
    final LogicalPlan p;
    final List<LogicalJoinNode> joins;

    /**
     * Constructor
     * * @param p
     * the logical plan being optimized
     * @param joins
     * the list of joins being performed
     */
    public JoinOptimizer(LogicalPlan p, List<LogicalJoinNode> joins) {
        this.p = p;
        this.joins = joins;
    }

    /**
     * Return best iterator for computing a given logical join, given the
     * specified statistics, and the provided left and right subplans.
     */
    public static OpIterator instantiateJoin(LogicalJoinNode lj,
                                             OpIterator plan1, OpIterator plan2) throws ParsingException {

        int t1id = 0, t2id = 0;
        OpIterator j;

        try {
            t1id = plan1.getTupleDesc().fieldNameToIndex(lj.f1QuantifiedName);
        } catch (NoSuchElementException e) {
            throw new ParsingException("Unknown field " + lj.f1QuantifiedName);
        }

        if (lj instanceof LogicalSubplanJoinNode) {
            t2id = 0;
        } else {
            try {
                t2id = plan2.getTupleDesc().fieldNameToIndex(
                        lj.f2QuantifiedName);
            } catch (NoSuchElementException e) {
                throw new ParsingException("Unknown field "
                        + lj.f2QuantifiedName);
            }
        }

        JoinPredicate p = new JoinPredicate(t1id, lj.p, t2id);

        if (lj.p == Predicate.Op.EQUALS) {

            try {
                // dynamically load HashEquiJoin -- if it doesn't exist, just
                // fall back on regular join
                Class<?> c = Class.forName("simpledb.execution.HashEquiJoin");
                java.lang.reflect.Constructor<?> ct = c.getConstructors()[0];
                j = (OpIterator) ct
                        .newInstance(new Object[] { p, plan1, plan2 });
            } catch (Exception e) {
                j = new Join(p, plan1, plan2);
            }
        } else {
            j = new Join(p, plan1, plan2);
        }

        return j;

    }

    /**
     * Estimate the cost of a join.
     */
    public double estimateJoinCost(LogicalJoinNode j, int card1, int card2,
                                   double cost1, double cost2) {
        if (j instanceof LogicalSubplanJoinNode) {
            return card1 + cost1 + cost2;
        } else {
            // Nested Loop Join 成本估算公式：scancost(t1) + ntups(t1) * scancost(t2) + ntups(t1) * ntups(t2)
            return cost1 + card1 * cost2 + (double) card1 * card2;
        }
    }

    /**
     * Estimate the cardinality of a join.
     */
    public int estimateJoinCardinality(LogicalJoinNode j, int card1, int card2,
                                       boolean t1pkey, boolean t2pkey, Map<String, TableStats> stats) {
        if (j instanceof LogicalSubplanJoinNode) {
            return card1;
        } else {
            return estimateTableJoinCardinality(j.p, j.t1Alias, j.t2Alias,
                    j.f1PureName, j.f2PureName, card1, card2, t1pkey, t2pkey,
                    stats, p.getTableAliasToIdMapping());
        }
    }

    /**
     * Estimate the join cardinality of two tables.
     */
    public static int estimateTableJoinCardinality(Predicate.Op joinOp,
                                                   String table1Alias, String table2Alias, String field1PureName,
                                                   String field2PureName, int card1, int card2, boolean t1pkey,
                                                   boolean t2pkey, Map<String, TableStats> stats,
                                                   Map<String, Integer> tableAliasToId) {
        int card = 1;

        if (joinOp == Predicate.Op.EQUALS) {
            if (t1pkey && t2pkey) {
                // 两边都是主键，结果不会超过较小的那张表
                card = Math.min(card1, card2);
            } else if (t1pkey) {
                // 左边是主键，右表的每一行最多在左表找到一个匹配，行数受限于右表
                card = card2;
            } else if (t2pkey) {
                // 右边是主键，同理受限于左表
                card = card1;
            } else {
                // 都不是主键，使用简单启发式：取两张表的较大值
                card = Math.max(card1, card2);
            }
        } else {
            // 对于范围扫描（如 >, < 等），假设会产生笛卡尔积的 30%
            card = (int) (0.3 * card1 * card2);
        }

        return card <= 0 ? 1 : card;
    }

    /**
     * Helper method to enumerate all of the subsets of a given size of a
     * specified vector.
     */
    public <T> Set<Set<T>> enumerateSubsets(List<T> v, int size) {
        Set<Set<T>> els = new HashSet<>();
        els.add(new HashSet<>());

        for (int i = 0; i < size; i++) {
            Set<Set<T>> newels = new HashSet<>();
            for (Set<T> s : els) {
                for (T t : v) {
                    Set<T> news = new HashSet<>(s);
                    if (news.add(t))
                        newels.add(news);
                }
            }
            els = newels;
        }

        return els;

    }

    /**
     * Compute a logical, reasonably efficient join on the specified tables.
     */
    public List<LogicalJoinNode> orderJoins(
            Map<String, TableStats> stats,
            Map<String, Double> filterSelectivities, boolean explain)
            throws ParsingException {

        // 如果没有 Join，直接返回
        if (joins == null || joins.isEmpty()) {
            return joins;
        }

        // 初始化计划缓存，用于存储每种组合的最小代价方式
        PlanCache pc = new PlanCache();
        CostCard bestFinalCard = null;

        // 1. 遍历所有可能长度的子集 i (从 1 到总 Join 数)
        for (int i = 1; i <= joins.size(); i++) {
            // 2. 枚举当前长度 i 的所有可能组合子集 s
            Set<Set<LogicalJoinNode>> subsets = enumerateSubsets(joins, i);

            for (Set<LogicalJoinNode> s : subsets) {
                double bestCostSoFar = Double.MAX_VALUE;
                CostCard bestCardForSubset = null;

                // 3. 寻找当前子集 s 的最优解：遍历移除其中一个节点 joinToRemove，将它拼接到子集 s - {joinToRemove} 上
                for (LogicalJoinNode joinToRemove : s) {
                    // computeCostAndCardOfSubplan 会自动去 pc 缓存里找 s - {joinToRemove} 的最优结果，然后算出拼接成本
                    CostCard cc = computeCostAndCardOfSubplan(stats, filterSelectivities, joinToRemove, s, bestCostSoFar, pc);
                    if (cc != null && cc.cost < bestCostSoFar) {
                        bestCostSoFar = cc.cost;
                        bestCardForSubset = cc;
                    }
                }

                // 4. 将当前子集 s 找出来的最优拼接方式，存入缓存供后续规模更大的集合使用
                if (bestCardForSubset != null) {
                    pc.addPlan(s, bestCardForSubset.cost, bestCardForSubset.card, bestCardForSubset.plan);

                    // 如果当前的组合规模等于全体 joins 的规模，说明我们找到了最终的全局解
                    if (i == joins.size()) {
                        bestFinalCard = bestCardForSubset;
                    }
                }
            }
        }

        // 如果成功找到了全局最优计划，执行 explain 输出并返回
        if (bestFinalCard != null) {
            if (explain) {
                printJoins(bestFinalCard.plan, pc, stats, filterSelectivities);
            }
            return bestFinalCard.plan;
        }

        return joins;
    }

    // ===================== Private Methods =================================

    @SuppressWarnings("unchecked")
    private CostCard computeCostAndCardOfSubplan(
            Map<String, TableStats> stats,
            Map<String, Double> filterSelectivities,
            LogicalJoinNode joinToRemove, Set<LogicalJoinNode> joinSet,
            double bestCostSoFar, PlanCache pc) throws ParsingException {

        LogicalJoinNode j = joinToRemove;

        List<LogicalJoinNode> prevBest;

        if (this.p.getTableId(j.t1Alias) == null)
            throw new ParsingException("Unknown table " + j.t1Alias);
        if (this.p.getTableId(j.t2Alias) == null)
            throw new ParsingException("Unknown table " + j.t2Alias);

        String table1Name = Database.getCatalog().getTableName(
                this.p.getTableId(j.t1Alias));
        String table2Name = Database.getCatalog().getTableName(
                this.p.getTableId(j.t2Alias));
        String table1Alias = j.t1Alias;
        String table2Alias = j.t2Alias;

        Set<LogicalJoinNode> news = new HashSet<>(joinSet);
        news.remove(j);

        double t1cost, t2cost;
        int t1card, t2card;
        boolean leftPkey, rightPkey;

        if (news.isEmpty()) { // base case -- both are base relations
            prevBest = new ArrayList<>();
            t1cost = stats.get(table1Name).estimateScanCost();
            t1card = stats.get(table1Name).estimateTableCardinality(
                    filterSelectivities.get(j.t1Alias));
            leftPkey = isPkey(j.t1Alias, j.f1PureName);

            t2cost = table2Alias == null ? 0 : stats.get(table2Name)
                    .estimateScanCost();
            t2card = table2Alias == null ? 0 : stats.get(table2Name)
                    .estimateTableCardinality(
                            filterSelectivities.get(j.t2Alias));
            rightPkey = table2Alias != null && isPkey(table2Alias,
                    j.f2PureName);
        } else {
            // news is not empty -- figure best way to join j to news
            prevBest = pc.getOrder(news);

            // possible that we have not cached an answer, if subset
            // includes a cross product
            if (prevBest == null) {
                return null;
            }

            double prevBestCost = pc.getCost(news);
            int bestCard = pc.getCard(news);

            // estimate cost of right subtree
            if (doesJoin(prevBest, table1Alias)) { // j.t1 is in prevBest
                t1cost = prevBestCost; // left side just has cost of whatever
                // left
                // subtree is
                t1card = bestCard;
                leftPkey = hasPkey(prevBest);

                t2cost = j.t2Alias == null ? 0 : stats.get(table2Name)
                        .estimateScanCost();
                t2card = j.t2Alias == null ? 0 : stats.get(table2Name)
                        .estimateTableCardinality(
                                filterSelectivities.get(j.t2Alias));
                rightPkey = j.t2Alias != null && isPkey(j.t2Alias,
                        j.f2PureName);
            } else if (doesJoin(prevBest, j.t2Alias)) { // j.t2 is in prevbest
                // (both
                // shouldn't be)
                t2cost = prevBestCost; // left side just has cost of whatever
                // left
                // subtree is
                t2card = bestCard;
                rightPkey = hasPkey(prevBest);
                t1cost = stats.get(table1Name).estimateScanCost();
                t1card = stats.get(table1Name).estimateTableCardinality(
                        filterSelectivities.get(j.t1Alias));
                leftPkey = isPkey(j.t1Alias, j.f1PureName);

            } else {
                // don't consider this plan if one of j.t1 or j.t2
                // isn't a table joined in prevBest (cross product)
                return null;
            }
        }

        // case where prevbest is left
        double cost1 = estimateJoinCost(j, t1card, t2card, t1cost, t2cost);

        LogicalJoinNode j2 = j.swapInnerOuter();
        double cost2 = estimateJoinCost(j2, t2card, t1card, t2cost, t1cost);
        if (cost2 < cost1) {
            boolean tmp;
            j = j2;
            cost1 = cost2;
            tmp = rightPkey;
            rightPkey = leftPkey;
            leftPkey = tmp;
        }
        if (cost1 >= bestCostSoFar)
            return null;

        CostCard cc = new CostCard();

        cc.card = estimateJoinCardinality(j, t1card, t2card, leftPkey,
                rightPkey, stats);
        cc.cost = cost1;
        cc.plan = new ArrayList<>(prevBest);
        cc.plan.add(j); // prevbest is left -- add new join to end
        return cc;
    }

    private boolean doesJoin(List<LogicalJoinNode> joinlist, String table) {
        for (LogicalJoinNode j : joinlist) {
            if (j.t1Alias.equals(table)
                    || (j.t2Alias != null && j.t2Alias.equals(table)))
                return true;
        }
        return false;
    }

    private boolean isPkey(String tableAlias, String field) {
        int tid1 = p.getTableId(tableAlias);
        String pkey1 = Database.getCatalog().getPrimaryKey(tid1);

        return pkey1.equals(field);
    }

    private boolean hasPkey(List<LogicalJoinNode> joinlist) {
        for (LogicalJoinNode j : joinlist) {
            if (isPkey(j.t1Alias, j.f1PureName)
                    || (j.t2Alias != null && isPkey(j.t2Alias, j.f2PureName)))
                return true;
        }
        return false;

    }

    private void printJoins(List<LogicalJoinNode> js, PlanCache pc,
                            Map<String, TableStats> stats,
                            Map<String, Double> selectivities) {

        JFrame f = new JFrame("Join Plan for " + p.getQuery());

        // Set the default close operation for the window,
        // or else the program won't exit when clicking close button
        f.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        f.setVisible(true);

        f.setSize(300, 500);

        Map<String, DefaultMutableTreeNode> m = new HashMap<>();

        DefaultMutableTreeNode root = null, treetop = null;
        HashSet<LogicalJoinNode> pathSoFar = new HashSet<>();
        boolean neither;

        System.out.println(js);
        for (LogicalJoinNode j : js) {
            pathSoFar.add(j);
            System.out.println("PATH SO FAR = " + pathSoFar);

            String table1Name = Database.getCatalog().getTableName(
                    this.p.getTableId(j.t1Alias));
            String table2Name = Database.getCatalog().getTableName(
                    this.p.getTableId(j.t2Alias));

            neither = true;

            root = new DefaultMutableTreeNode("Join " + j + " (Cost ="
                    + pc.getCost(pathSoFar) + ", card = "
                    + pc.getCard(pathSoFar) + ")");
            DefaultMutableTreeNode n = m.get(j.t1Alias);
            if (n == null) { // never seen this table before
                n = new DefaultMutableTreeNode(j.t1Alias
                        + " (Cost = "
                        + stats.get(table1Name).estimateScanCost()
                        + ", card = "
                        + stats.get(table1Name).estimateTableCardinality(
                        selectivities.get(j.t1Alias)) + ")");
                root.add(n);
            } else {
                // make left child root n
                root.add(n);
                neither = false;
            }
            m.put(j.t1Alias, root);

            n = m.get(j.t2Alias);
            if (n == null) { // never seen this table before

                n = new DefaultMutableTreeNode(
                        j.t2Alias == null ? "Subplan"
                                : (j.t2Alias
                                + " (Cost = "
                                + stats.get(table2Name)
                                .estimateScanCost()
                                + ", card = "
                                + stats.get(table2Name)
                                .estimateTableCardinality(
                                        selectivities
                                                .get(j.t2Alias)) + ")"));
                root.add(n);
            } else {
                // make right child root n
                root.add(n);
                neither = false;
            }
            m.put(j.t2Alias, root);

            // unless this table doesn't join with other tables,
            // all tables are accessed from root
            if (!neither) {
                for (String key : m.keySet()) {
                    m.put(key, root);
                }
            }

            treetop = root;
        }

        JTree tree = new JTree(treetop);
        JScrollPane treeView = new JScrollPane(tree);

        tree.setShowsRootHandles(true);

        // Set the icon for leaf nodes.
        ImageIcon leafIcon = new ImageIcon("join.jpg");
        DefaultTreeCellRenderer renderer = new DefaultTreeCellRenderer();
        renderer.setOpenIcon(leafIcon);
        renderer.setClosedIcon(leafIcon);

        tree.setCellRenderer(renderer);

        f.setSize(300, 500);

        f.add(treeView);
        for (int i = 0; i < tree.getRowCount(); i++) {
            tree.expandRow(i);
        }

        if (js.size() == 0) {
            f.add(new JLabel("No joins in plan."));
        }

        f.pack();

    }

}
