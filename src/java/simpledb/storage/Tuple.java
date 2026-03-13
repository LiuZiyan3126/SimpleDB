package simpledb.storage;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Tuple maintains information about the contents of a tuple. Tuples have a
 * specified schema specified by a TupleDesc object and contain Field objects
 * with the data for each field.
 */
public class Tuple implements Serializable {

    private static final long serialVersionUID = 1L;

    private TupleDesc tupleDesc;
    private RecordId recordId;
    private final List<Field> fields;

    /**
     * Create a new tuple with the specified schema (type).
     *
     * @param td
     * the schema of this tuple. It must be a valid TupleDesc
     * instance with at least one field.
     */
    public Tuple(TupleDesc td) {
        this.tupleDesc = td;
        this.fields = new ArrayList<>(td.numFields());
        // 初始化 null 占位，保证可以通过 setField 按索引设值
        for (int i = 0; i < td.numFields(); i++) {
            this.fields.add(null);
        }
    }

    /**
     * @return The TupleDesc representing the schema of this tuple.
     */
    public TupleDesc getTupleDesc() {
        return this.tupleDesc;
    }

    /**
     * @return The RecordId representing the location of this tuple on disk. May
     * be null.
     */
    public RecordId getRecordId() {
        return this.recordId;
    }

    /**
     * Set the RecordId information for this tuple.
     *
     * @param rid
     * the new RecordId for this tuple.
     */
    public void setRecordId(RecordId rid) {
        this.recordId = rid;
    }

    /**
     * Change the value of the ith field of this tuple.
     *
     * @param i
     * index of the field to change. It must be a valid index.
     * @param f
     * new value for the field.
     */
    public void setField(int i, Field f) {
        if (i >= 0 && i < fields.size()) {
            this.fields.set(i, f);
        }
    }

    /**
     * @return the value of the ith field, or null if it has not been set.
     *
     * @param i
     * field index to return. Must be a valid index.
     */
    public Field getField(int i) {
        if (i >= 0 && i < fields.size()) {
            return this.fields.get(i);
        }
        return null;
    }

    /**
     * Returns the contents of this Tuple as a string. Note that to pass the
     * system tests, the format needs to be as follows:
     *
     * column1\tcolumn2\tcolumn3\t...\tcolumnN
     *
     * where \t is any whitespace (except a newline)
     */
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i) != null) {
                sb.append(fields.get(i).toString());
            } else {
                sb.append("null");
            }
            if (i < fields.size() - 1) {
                sb.append("\t");
            }
        }
        return sb.append("\n").toString();
    }

    /**
     * @return
     * An iterator which iterates over all the fields of this tuple
     * */
    public Iterator<Field> fields() {
        return this.fields.iterator();
    }

    /**
     * reset the TupleDesc of this tuple (only affecting the TupleDesc)
     * */
    public void resetTupleDesc(TupleDesc td)
    {
        this.tupleDesc = td;
    }
}
