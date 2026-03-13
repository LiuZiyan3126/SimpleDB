package simpledb.execution;

import simpledb.common.Database;
import simpledb.common.DbException;
import simpledb.common.Type;
import simpledb.storage.DbFile;
import simpledb.storage.DbFileIterator;
import simpledb.storage.Tuple;
import simpledb.storage.TupleDesc;
import simpledb.transaction.TransactionAbortedException;
import simpledb.transaction.TransactionId;

import java.util.NoSuchElementException;

/**
 * SeqScan is an implementation of a sequential scan access method that reads
 * each tuple of a table in no particular order (e.g., as they are laid out on
 * disk).
 */
public class SeqScan implements OpIterator {

    private static final long serialVersionUID = 1L;

    private DbFileIterator iterator;
    private TransactionId tid;
    private int tableid;
    private String tableAlias;

    /**
     * Creates a sequential scan over the specified table as a part of the
     * specified transaction.
     *
     * @param tid
     * The transaction this scan is running as a part of.
     * @param tableid
     * the table to scan.
     * @param tableAlias
     * the alias of this table (needed by the parser); the returned
     * tupleDesc should have fields with name tableAlias.fieldName
     * (note: this class is not responsible for handling a case where
     * tableAlias or fieldName are null. It shouldn't crash if they
     * are, but the resulting name can be null.fieldName,
     * tableAlias.null, or null.null).
     */
    public SeqScan(TransactionId tid, int tableid, String tableAlias) {
        this.tid = tid;
        this.tableid = tableid;
        this.tableAlias = tableAlias;
    }

    /**
     * @return
     * return the table name of the table the operator scans. This should
     * be the actual name of the table in the catalog of the database
     * */
    public String getTableName() {
        return Database.getCatalog().getTableName(tableid);
    }

    /**
     * @return Return the alias of the table this operator scans.
     * */
    public String getAlias()
    {
        return this.tableAlias;
    }

    /**
     * Reset the tableid, and tableAlias of this operator.
     * @param tableid
     * the table to scan.
     * @param tableAlias
     * the alias of this table (needed by the parser); the returned
     * tupleDesc should have fields with name tableAlias.fieldName
     * (note: this class is not responsible for handling a case where
     * tableAlias or fieldName are null. It shouldn't crash if they
     * are, but the resulting name can be null.fieldName,
     * tableAlias.null, or null.null).
     */
    public void reset(int tableid, String tableAlias) {
        this.tableid = tableid;
        this.tableAlias = tableAlias;
    }

    public SeqScan(TransactionId tid, int tableId) {
        this(tid, tableId, Database.getCatalog().getTableName(tableId));
    }

    public void open() throws DbException, TransactionAbortedException {
        // 从 Catalog 获取文件，然后打开它的迭代器
        DbFile file = Database.getCatalog().getDatabaseFile(tableid);
        iterator = file.iterator(tid);
        iterator.open();
    }

    /**
     * Returns the TupleDesc with field names from the underlying HeapFile,
     * prefixed with the tableAlias string from the constructor. This prefix
     * becomes useful when joining tables containing a field(s) with the same
     * name.  The alias and name should be separated with a "." character
     * (e.g., "alias.fieldName").
     *
     * @return the TupleDesc with field names from the underlying HeapFile,
     * prefixed with the tableAlias string from the constructor.
     */
    public TupleDesc getTupleDesc() {
        TupleDesc originalTd = Database.getCatalog().getTupleDesc(tableid);
        int numFields = originalTd.numFields();
        Type[] types = new Type[numFields];
        String[] names = new String[numFields];

        // 重新拼接字段名，格式为 "alias.fieldName"
        for (int i = 0; i < numFields; i++) {
            types[i] = originalTd.getFieldType(i);
            names[i] = tableAlias + "." + originalTd.getFieldName(i);
        }

        return new TupleDesc(types, names);
    }

    public boolean hasNext() throws TransactionAbortedException, DbException {
        if (iterator == null) {
            return false;
        }
        return iterator.hasNext();
    }

    public Tuple next() throws NoSuchElementException,
            TransactionAbortedException, DbException {
        if (iterator == null) {
            throw new NoSuchElementException("Iterator is not open");
        }
        return iterator.next();
    }

    public void close() {
        if (iterator != null) {
            iterator.close();
        }
    }

    public void rewind() throws DbException, NoSuchElementException,
            TransactionAbortedException {
        if (iterator != null) {
            iterator.rewind();
        }
    }
}
