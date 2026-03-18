package simpledb.optimizer;

import simpledb.execution.Predicate;

/** A class to represent a fixed-width histogram over a single integer-based field.
 */
public class IntHistogram {

    private final int[] buckets;
    private final int min;
    private final int max;
    private final double width;
    private int ntups;

    /**
     * Create a new IntHistogram.
     */
    public IntHistogram(int buckets, int min, int max) {
        this.buckets = new int[buckets];
        this.min = min;
        this.max = max;
        // 计算每个桶的跨度宽度 (w)。注意总范围需要 +1 才是实际跨度
        this.width = (double) (max - min + 1) / buckets;
        this.ntups = 0;
    }

    /**
     * 辅助方法：根据输入值 v 计算其应该落在哪个桶的索引中
     */
    private int getIndex(int v) {
        if (v < min) return -1;
        if (v > max) return buckets.length;
        int idx = (int) ((v - min) / width);
        // 边界处理：如果恰好计算到了 length（因为浮点精度），归入最后一个桶
        if (idx >= buckets.length) {
            idx = buckets.length - 1;
        }
        return idx;
    }

    /**
     * Add a value to the set of values that you are keeping a histogram of.
     */
    public void addValue(int v) {
        int idx = getIndex(v);
        if (idx >= 0 && idx < buckets.length) {
            buckets[idx]++;
            ntups++;
        }
    }

    /**
     * Estimate the selectivity of a particular predicate and operand on this table.
     */
    public double estimateSelectivity(Predicate.Op op, int v) {
        if (ntups == 0) return 0.0;

        if (op == Predicate.Op.EQUALS) {
            int idx = getIndex(v);
            if (idx < 0 || idx >= buckets.length) return 0.0;
            return (buckets[idx] / width) / ntups;
        }

        if (op == Predicate.Op.NOT_EQUALS) {
            return 1.0 - estimateSelectivity(Predicate.Op.EQUALS, v);
        }

        if (op == Predicate.Op.GREATER_THAN) {
            if (v < min) return 1.0;
            if (v >= max) return 0.0;
            int idx = getIndex(v);
            if (idx < 0) return 1.0;
            if (idx >= buckets.length) return 0.0;

            // 当前桶中，大于 const 的部分占比
            double b_f = (double) buckets[idx] / ntups;
            double b_right = min + (idx + 1) * width;
            double b_part = (b_right - v) / width;
            double sel = b_f * b_part;

            // 加上右侧所有完全被包含的桶的占比
            for (int i = idx + 1; i < buckets.length; i++) {
                sel += (double) buckets[i] / ntups;
            }
            return sel;
        }

        if (op == Predicate.Op.LESS_THAN) {
            if (v <= min) return 0.0;
            if (v > max) return 1.0;
            int idx = getIndex(v);
            if (idx < 0) return 0.0;
            if (idx >= buckets.length) return 1.0;

            // 当前桶中，小于 const 的部分占比
            double b_f = (double) buckets[idx] / ntups;
            double b_left = min + idx * width;
            double b_part = (v - b_left) / width;
            double sel = b_f * b_part;

            // 加上左侧所有完全被包含的桶的占比
            for (int i = 0; i < idx; i++) {
                sel += (double) buckets[i] / ntups;
            }
            return sel;
        }

        // 修改点：老老实实地拆分成 等于 + 大于，并限制最大值为 1.0
        if (op == Predicate.Op.GREATER_THAN_OR_EQ) {
            return Math.min(1.0, estimateSelectivity(Predicate.Op.EQUALS, v) + estimateSelectivity(Predicate.Op.GREATER_THAN, v));
        }

        // 修改点：老老实实地拆分成 等于 + 小于，并限制最大值为 1.0
        if (op == Predicate.Op.LESS_THAN_OR_EQ) {
            return Math.min(1.0, estimateSelectivity(Predicate.Op.EQUALS, v) + estimateSelectivity(Predicate.Op.LESS_THAN, v));
        }

        return 0.0;
    }

    /**
     * @return the average selectivity of this histogram.
     */
    public double avgSelectivity() {
        return 1.0;
    }

    public String toString() {
        return String.format("IntHistogram(buckets=%d, min=%d, max=%d)", buckets.length, min, max);
    }
}
