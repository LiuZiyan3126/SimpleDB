package simpledb.optimizer;

import simpledb.common.Database;
import simpledb.common.Type;
import simpledb.execution.Predicate;
import simpledb.storage.*;
import simpledb.transaction.TransactionId;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * TableStats represents statistics (e.g., histograms) about base tables in a
 * query.
 * * This class is not needed in implementing lab1 and lab2.
 */
public class TableStats {

    private static final ConcurrentMap<String, TableStats> statsMap = new ConcurrentHashMap<>();

    static final int IOCOSTPERPAGE = 1000;

    public static TableStats getTableStats(String tablename) {
        return statsMap.get(tablename);
    }

    public static void setTableStats(String tablename, TableStats stats) {
        statsMap.put(tablename, stats);
    }

    public static void setStatsMap(Map<String,TableStats> s)
    {
        try {
            java.lang.reflect.Field statsMapF = TableStats.class.getDeclaredField("statsMap");
            statsMapF.setAccessible(true);
            statsMapF.set(null, s);
        } catch (NoSuchFieldException | IllegalAccessException | IllegalArgumentException | SecurityException e) {
            e.printStackTrace();
        }

    }

    public static Map<String, TableStats> getStatsMap() {
        return statsMap;
    }

    public static void computeStatistics() {
        Iterator<Integer> tableIt = Database.getCatalog().tableIdIterator();

        System.out.println("Computing table stats.");
        while (tableIt.hasNext()) {
            int tableid = tableIt.next();
            TableStats s = new TableStats(tableid, IOCOSTPERPAGE);
            setTableStats(Database.getCatalog().getTableName(tableid), s);
        }
        System.out.println("Done.");
    }

    /**
     * Number of bins for the histogram. Feel free to increase this value over
     * 100, though our tests assume that you have at least 100 bins in your
     * histograms.
     */
    static final int NUM_HIST_BINS = 100;

    // 添加核心属性
    private int ioCostPerPage;
    private int numPages;
    private int totalTuples;
    private Map<Integer, IntHistogram> intHistograms;
    private Map<Integer, StringHistogram> stringHistograms;
    private TupleDesc td;

    /**
     * Create a new TableStats object, that keeps track of statistics on each
     * column of a table
     * * @param tableid
     * The table over which to compute statistics
     * @param ioCostPerPage
     * The cost per page of IO. This doesn't differentiate between
     * sequential-scan IO and disk seeks.
     */
    public TableStats(int tableid, int ioCostPerPage) {
        this.ioCostPerPage = ioCostPerPage;
        this.totalTuples = 0;
        this.intHistograms = new HashMap<>();
        this.stringHistograms = new HashMap<>();

        // 获取底层文件并转换为 HeapFile 以获取页数
        HeapFile file = (HeapFile) Database.getCatalog().getDatabaseFile(tableid);
        this.numPages = file.numPages();
        this.td = file.getTupleDesc();

        Map<Integer, Integer> mins = new HashMap<>();
        Map<Integer, Integer> maxs = new HashMap<>();

        // 伪造一个事务ID用于读取文件
        TransactionId tid = new TransactionId();
        DbFileIterator iterator = file.iterator(tid);

        try {
            // 第一遍扫描：计算总行数，并找出所有 INT 字段的最小值和最大值
            iterator.open();
            while (iterator.hasNext()) {
                this.totalTuples++;
                Tuple t = iterator.next();
                for (int i = 0; i < td.numFields(); i++) {
                    if (td.getFieldType(i) == Type.INT_TYPE) {
                        int val = ((IntField) t.getField(i)).getValue();
                        mins.put(i, Math.min(mins.getOrDefault(i, Integer.MAX_VALUE), val));
                        maxs.put(i, Math.max(maxs.getOrDefault(i, Integer.MIN_VALUE), val));
                    }
                }
            }

            // 实例化直方图
            for (int i = 0; i < td.numFields(); i++) {
                if (td.getFieldType(i) == Type.INT_TYPE) {
                    // 如果表为空，min 和 max 默认给 0 即可
                    int min = mins.getOrDefault(i, 0);
                    int max = maxs.getOrDefault(i, 0);
                    intHistograms.put(i, new IntHistogram(NUM_HIST_BINS, min, max));
                } else {
                    stringHistograms.put(i, new StringHistogram(NUM_HIST_BINS));
                }
            }

            // 第二遍扫描：将具体数据填入各个直方图中
            iterator.rewind();
            while (iterator.hasNext()) {
                Tuple t = iterator.next();
                for (int i = 0; i < td.numFields(); i++) {
                    if (td.getFieldType(i) == Type.INT_TYPE) {
                        int val = ((IntField) t.getField(i)).getValue();
                        intHistograms.get(i).addValue(val);
                    } else {
                        String val = ((StringField) t.getField(i)).getValue();
                        stringHistograms.get(i).addValue(val);
                    }
                }
            }
            iterator.close();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Estimates the cost of sequentially scanning the file, given that the cost
     * to read a page is costPerPageIO. You can assume that there are no seeks
     * and that no pages are in the buffer pool.
     */
    public double estimateScanCost() {
        return (double) numPages * ioCostPerPage;
    }

    /**
     * This method returns the number of tuples in the relation, given that a
     * predicate with selectivity selectivityFactor is applied.
     */
    public int estimateTableCardinality(double selectivityFactor) {
        return (int) (totalTuples * selectivityFactor);
    }

    /**
     * The average selectivity of the field under op.
     */
    public double avgSelectivity(int field, Predicate.Op op) {
        return 1.0;
    }

    /**
     * Estimate the selectivity of predicate <tt>field op constant</tt> on the
     * table.
     */
    public double estimateSelectivity(int field, Predicate.Op op, Field constant) {
        if (td.getFieldType(field) == Type.INT_TYPE) {
            return intHistograms.get(field).estimateSelectivity(op, ((IntField) constant).getValue());
        } else {
            return stringHistograms.get(field).estimateSelectivity(op, ((StringField) constant).getValue());
        }
    }

    /**
     * return the total number of tuples in this table
     */
    public int totalTuples() {
        return this.totalTuples;
    }
}
