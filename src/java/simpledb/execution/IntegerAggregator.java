package simpledb.execution;

import simpledb.common.Type;
import simpledb.storage.Field;
import simpledb.storage.IntField;
import simpledb.storage.Tuple;
import simpledb.storage.TupleDesc;
import simpledb.storage.TupleIterator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Knows how to compute some aggregate over a set of IntFields.
 */
public class IntegerAggregator implements Aggregator {

    private static final long serialVersionUID = 1L;

    private final int gbfield;
    private final Type gbfieldtype;
    private final int afield;
    private final Op what;

    // 用于记录每个分组的聚合结果
    private final Map<Field, Integer> aggregates;
    // 用于记录每个分组的元素个数（计算 AVG 时需要用到总和与个数）
    private final Map<Field, Integer> counts;

    /**
     * Aggregate constructor
     */
    public IntegerAggregator(int gbfield, Type gbfieldtype, int afield, Op what) {
        this.gbfield = gbfield;
        this.gbfieldtype = gbfieldtype;
        this.afield = afield;
        this.what = what;
        this.aggregates = new HashMap<>();
        this.counts = new HashMap<>();
    }

    /**
     * Merge a new tuple into the aggregate, grouping as indicated in the
     * constructor
     */
    public void mergeTupleIntoGroup(Tuple tup) {
        // 如果没有分组，则统一使用 null 作为 key
        Field groupField = (gbfield == Aggregator.NO_GROUPING) ? null : tup.getField(gbfield);
        int value = ((IntField) tup.getField(afield)).getValue();

        if (!aggregates.containsKey(groupField)) {
            // 初始化
            if (what == Op.COUNT) {
                aggregates.put(groupField, 0);
            } else if (what == Op.MIN || what == Op.MAX || what == Op.SUM || what == Op.AVG) {
                aggregates.put(groupField, value);
            }
            counts.put(groupField, 1);
        } else {
            // 更新已有的聚合值
            int currentAgg = aggregates.get(groupField);
            int currentCount = counts.get(groupField);

            switch (what) {
                case MIN:
                    aggregates.put(groupField, Math.min(currentAgg, value));
                    break;
                case MAX:
                    aggregates.put(groupField, Math.max(currentAgg, value));
                    break;
                case SUM:
                case AVG: // 对于 AVG，我们先在此处累加总和，输出时再除以 count
                    aggregates.put(groupField, currentAgg + value);
                    break;
                case COUNT:
                    aggregates.put(groupField, currentAgg + 1);
                    break;
            }
            counts.put(groupField, currentCount + 1);
        }
    }

    /**
     * Create a OpIterator over group aggregate results.
     */
    public OpIterator iterator() {
        // 构建返回结果的 TupleDesc
        TupleDesc td;
        if (gbfield == Aggregator.NO_GROUPING) {
            td = new TupleDesc(new Type[]{Type.INT_TYPE});
        } else {
            td = new TupleDesc(new Type[]{gbfieldtype, Type.INT_TYPE});
        }

        List<Tuple> tuples = new ArrayList<>();

        for (Map.Entry<Field, Integer> entry : aggregates.entrySet()) {
            Tuple tuple = new Tuple(td);
            int finalValue = entry.getValue();

            // 如果是求平均值，在这里进行整数除法
            if (what == Op.AVG) {
                finalValue = finalValue / counts.get(entry.getKey());
            }

            if (gbfield == Aggregator.NO_GROUPING) {
                tuple.setField(0, new IntField(finalValue));
            } else {
                tuple.setField(0, entry.getKey());
                tuple.setField(1, new IntField(finalValue));
            }
            tuples.add(tuple);
        }

        return new TupleIterator(td, tuples);
    }
}
