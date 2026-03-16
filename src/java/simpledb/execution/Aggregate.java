package simpledb.execution;

import simpledb.common.DbException;
import simpledb.common.Type;
import simpledb.storage.Tuple;
import simpledb.storage.TupleDesc;
import simpledb.transaction.TransactionAbortedException;

import java.util.NoSuchElementException;

/**
 * The Aggregation operator that computes an aggregate (e.g., sum, avg, max,
 * min). Note that we only support aggregates over a single column, grouped by a
 * single column.
 */
public class Aggregate extends Operator {

    private static final long serialVersionUID = 1L;

    private OpIterator child;
    private final int afield;
    private final int gfield;
    private final Aggregator.Op aop;

    private final Aggregator aggregator;
    private OpIterator aggIterator;

    /**
     * Constructor.
     */
    public Aggregate(OpIterator child, int afield, int gfield, Aggregator.Op aop) {
        this.child = child;
        this.afield = afield;
        this.gfield = gfield;
        this.aop = aop;

        Type gfieldtype = (gfield == Aggregator.NO_GROUPING) ? null : child.getTupleDesc().getFieldType(gfield);
        Type afieldtype = child.getTupleDesc().getFieldType(afield);

        if (afieldtype == Type.INT_TYPE) {
            this.aggregator = new IntegerAggregator(gfield, gfieldtype, afield, aop);
        } else if (afieldtype == Type.STRING_TYPE) {
            this.aggregator = new StringAggregator(gfield, gfieldtype, afield, aop);
        } else {
            throw new IllegalArgumentException("Unknown field type");
        }
    }

    public int groupField() {
        return this.gfield;
    }

    public String groupFieldName() {
        if (gfield == Aggregator.NO_GROUPING) {
            return null;
        }
        return child.getTupleDesc().getFieldName(gfield);
    }

    public int aggregateField() {
        return this.afield;
    }

    public String aggregateFieldName() {
        return child.getTupleDesc().getFieldName(afield);
    }

    public Aggregator.Op aggregateOp() {
        return this.aop;
    }

    public static String nameOfAggregatorOp(Aggregator.Op aop) {
        return aop.toString();
    }

    public void open() throws NoSuchElementException, DbException,
            TransactionAbortedException {
        child.open();
        // 阻塞式地读取所有子节点数据并进行聚合运算
        while (child.hasNext()) {
            aggregator.mergeTupleIntoGroup(child.next());
        }

        // 获取聚合后的结果迭代器并打开
        aggIterator = aggregator.iterator();
        aggIterator.open();

        super.open();
    }

    protected Tuple fetchNext() throws TransactionAbortedException, DbException {
        if (aggIterator != null && aggIterator.hasNext()) {
            return aggIterator.next();
        }
        return null;
    }

    public void rewind() throws DbException, TransactionAbortedException {
        if (aggIterator != null) {
            aggIterator.rewind();
        }
    }

    /**
     * Returns the TupleDesc of this Aggregate.
     */
    public TupleDesc getTupleDesc() {
        Type[] types;
        String[] names;

        String aggName = aop.toString() + "(" + child.getTupleDesc().getFieldName(afield) + ")";

        if (gfield == Aggregator.NO_GROUPING) {
            types = new Type[]{Type.INT_TYPE};
            names = new String[]{aggName};
        } else {
            types = new Type[]{child.getTupleDesc().getFieldType(gfield), Type.INT_TYPE};
            names = new String[]{child.getTupleDesc().getFieldName(gfield), aggName};
        }
        return new TupleDesc(types, names);
    }

    public void close() {
        super.close();
        if (aggIterator != null) {
            aggIterator.close();
        }
        child.close();
    }

    @Override
    public OpIterator[] getChildren() {
        return new OpIterator[]{this.child};
    }

    @Override
    public void setChildren(OpIterator[] children) {
        if (children != null && children.length > 0) {
            this.child = children[0];
        }
    }
}
