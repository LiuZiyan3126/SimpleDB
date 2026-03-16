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
 * Knows how to compute some aggregate over a set of StringFields.
 */
public class StringAggregator implements Aggregator {

    private static final long serialVersionUID = 1L;

    private final int gbfield;
    private final Type gbfieldtype;
    private final int afield;
    private final Op what;

    private final Map<Field, Integer> counts;

    /**
     * Aggregate constructor
     */
    public StringAggregator(int gbfield, Type gbfieldtype, int afield, Op what) {
        if (what != Op.COUNT) {
            throw new IllegalArgumentException("StringAggregator only supports COUNT");
        }
        this.gbfield = gbfield;
        this.gbfieldtype = gbfieldtype;
        this.afield = afield;
        this.what = what;
        this.counts = new HashMap<>();
    }

    /**
     * Merge a new tuple into the aggregate, grouping as indicated in the constructor
     */
    public void mergeTupleIntoGroup(Tuple tup) {
        Field groupField = (gbfield == Aggregator.NO_GROUPING) ? null : tup.getField(gbfield);
        counts.put(groupField, counts.getOrDefault(groupField, 0) + 1);
    }

    /**
     * Create a OpIterator over group aggregate results.
     */
    public OpIterator iterator() {
        TupleDesc td;
        if (gbfield == Aggregator.NO_GROUPING) {
            td = new TupleDesc(new Type[]{Type.INT_TYPE});
        } else {
            td = new TupleDesc(new Type[]{gbfieldtype, Type.INT_TYPE});
        }

        List<Tuple> tuples = new ArrayList<>();

        for (Map.Entry<Field, Integer> entry : counts.entrySet()) {
            Tuple tuple = new Tuple(td);
            if (gbfield == Aggregator.NO_GROUPING) {
                tuple.setField(0, new IntField(entry.getValue()));
            } else {
                tuple.setField(0, entry.getKey());
                tuple.setField(1, new IntField(entry.getValue()));
            }
            tuples.add(tuple);
        }

        return new TupleIterator(td, tuples);
    }
}
