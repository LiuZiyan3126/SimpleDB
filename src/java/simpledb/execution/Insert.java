package simpledb.execution;

import simpledb.common.Database;
import simpledb.common.DbException;
import simpledb.common.Type;
import simpledb.storage.IntField;
import simpledb.storage.Tuple;
import simpledb.storage.TupleDesc;
import simpledb.transaction.TransactionAbortedException;
import simpledb.transaction.TransactionId;

import java.io.IOException;

/**
 * Inserts tuples read from the child operator into the tableId specified in the
 * constructor
 */
public class Insert extends Operator {

    private static final long serialVersionUID = 1L;

    private final TransactionId tid;
    private OpIterator child;
    private final int tableId;
    private final TupleDesc td;
    private boolean fetched; // 关键状态：防止多次返回导致上层死循环

    /**
     * Constructor.
     */
    public Insert(TransactionId t, OpIterator child, int tableId)
            throws DbException {
        this.tid = t;
        this.child = child;
        this.tableId = tableId;
        // 返回的 Tuple 只有一个字段：插入的记录数 (Type.INT_TYPE)
        this.td = new TupleDesc(new Type[]{Type.INT_TYPE});
        this.fetched = false;
    }

    public TupleDesc getTupleDesc() {
        return this.td;
    }

    public void open() throws DbException, TransactionAbortedException {
        child.open();
        super.open();
        fetched = false;
    }

    public void close() {
        super.close();
        child.close();
        fetched = false;
    }

    public void rewind() throws DbException, TransactionAbortedException {
        child.rewind();
        fetched = false;
    }

    /**
     * Inserts tuples read from child into the tableId specified by the
     * constructor.
     */
    protected Tuple fetchNext() throws TransactionAbortedException, DbException {
        // 如果已经执行过插入并返回过结果，后续必须返回 null
        if (fetched) {
            return null;
        }

        int count = 0;
        // 遍历子算子的所有数据，依次插入到底层 BufferPool 中
        while (child.hasNext()) {
            Tuple t = child.next();
            try {
                Database.getBufferPool().insertTuple(tid, tableId, t);
                count++;
            } catch (IOException e) {
                throw new DbException("IOException occurred during insertion: " + e.getMessage());
            }
        }

        // 标记为已处理
        fetched = true;

        // 构造返回结果，包含成功插入的行数
        Tuple result = new Tuple(td);
        result.setField(0, new IntField(count));
        return result;
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
