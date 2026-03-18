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
 * The delete operator. Delete reads tuples from its child operator and removes
 * them from the table they belong to.
 */
public class Delete extends Operator {

    private static final long serialVersionUID = 1L;

    private final TransactionId tid;
    private OpIterator child;
    private final TupleDesc td;
    private boolean fetched; // 关键状态：防止多次返回

    /**
     * Constructor specifying the transaction that this delete belongs to as
     * well as the child to read from.
     */
    public Delete(TransactionId t, OpIterator child) {
        this.tid = t;
        this.child = child;
        // 返回的 Tuple 只有一个字段：删除的记录数 (Type.INT_TYPE)
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
     * Deletes tuples as they are read from the child operator.
     */
    protected Tuple fetchNext() throws TransactionAbortedException, DbException {
        // 如果已经执行过删除并返回过结果，后续必须返回 null
        if (fetched) {
            return null;
        }

        int count = 0;
        // 遍历子算子的所有数据，依次告诉 BufferPool 删掉它们
        while (child.hasNext()) {
            Tuple t = child.next();
            try {
                Database.getBufferPool().deleteTuple(tid, t);
                count++;
            } catch (IOException e) {
                throw new DbException("IOException occurred during deletion: " + e.getMessage());
            }
        }

        // 标记为已处理
        fetched = true;

        // 构造返回结果，包含成功删除的行数
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
