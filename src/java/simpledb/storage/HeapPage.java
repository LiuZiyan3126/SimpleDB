package simpledb.storage;

import simpledb.common.Database;
import simpledb.common.DbException;
import simpledb.transaction.TransactionId;

import java.io.*;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Each instance of HeapPage stores data for one page of HeapFiles and
 * implements the Page interface that is used by BufferPool.
 */
public class HeapPage implements Page {

    final HeapPageId pid;
    final TupleDesc td;
    final byte[] header;
    final Tuple[] tuples;
    final int numSlots;

    byte[] oldData;
    private final Byte oldDataLock = (byte) 0;

    // 用于记录脏页状态和修改该页的事务
    private boolean isDirty = false;
    private TransactionId dirtier = null;

    public HeapPage(HeapPageId id, byte[] data) throws IOException {
        this.pid = id;
        this.td = Database.getCatalog().getTupleDesc(id.getTableId());
        this.numSlots = getNumTuples();
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data));

        header = new byte[getHeaderSize()];
        for (int i=0; i<header.length; i++)
            header[i] = dis.readByte();

        tuples = new Tuple[numSlots];
        try{
            for (int i=0; i<tuples.length; i++)
                tuples[i] = readNextTuple(dis,i);
        }catch(NoSuchElementException e){
            e.printStackTrace();
        }
        dis.close();

        setBeforeImage();
    }

    private int getNumTuples() {
        return (BufferPool.getPageSize() * 8) / (td.getSize() * 8 + 1);
    }

    private int getHeaderSize() {
        return (int) Math.ceil(getNumTuples() / 8.0);
    }

    public HeapPage getBeforeImage(){
        try {
            byte[] oldDataRef = null;
            synchronized(oldDataLock)
            {
                oldDataRef = oldData;
            }
            return new HeapPage(pid,oldDataRef);
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(1);
        }
        return null;
    }

    public void setBeforeImage() {
        synchronized(oldDataLock)
        {
            oldData = getPageData().clone();
        }
    }

    public HeapPageId getId() {
        return pid;
    }

    private Tuple readNextTuple(DataInputStream dis, int slotId) throws NoSuchElementException {
        if (!isSlotUsed(slotId)) {
            for (int i=0; i<td.getSize(); i++) {
                try {
                    dis.readByte();
                } catch (IOException e) {
                    throw new NoSuchElementException("error reading empty tuple");
                }
            }
            return null;
        }

        Tuple t = new Tuple(td);
        RecordId rid = new RecordId(pid, slotId);
        t.setRecordId(rid);
        try {
            for (int j=0; j<td.numFields(); j++) {
                Field f = td.getFieldType(j).parse(dis);
                t.setField(j, f);
            }
        } catch (java.text.ParseException e) {
            e.printStackTrace();
            throw new NoSuchElementException("parsing error!");
        }

        return t;
    }

    public byte[] getPageData() {
        int len = BufferPool.getPageSize();
        ByteArrayOutputStream baos = new ByteArrayOutputStream(len);
        DataOutputStream dos = new DataOutputStream(baos);

        for (byte b : header) {
            try {
                dos.writeByte(b);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        for (int i=0; i<tuples.length; i++) {
            if (!isSlotUsed(i)) {
                for (int j=0; j<td.getSize(); j++) {
                    try {
                        dos.writeByte(0);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                continue;
            }

            for (int j=0; j<td.numFields(); j++) {
                Field f = tuples[i].getField(j);
                try {
                    f.serialize(dos);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        int zerolen = BufferPool.getPageSize() - (header.length + td.getSize() * tuples.length);
        byte[] zeroes = new byte[zerolen];
        try {
            dos.write(zeroes, 0, zerolen);
        } catch (IOException e) {
            e.printStackTrace();
        }

        try {
            dos.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return baos.toByteArray();
    }

    public static byte[] createEmptyPageData() {
        int len = BufferPool.getPageSize();
        return new byte[len]; //all 0
    }

    public void deleteTuple(Tuple t) throws DbException {
        RecordId rid = t.getRecordId();
        // 校验该元组是否在这页上
        if (rid == null || !rid.getPageId().equals(this.pid)) {
            throw new DbException("Tuple is not on this page");
        }
        int slot = rid.getTupleNumber();
        // 校验该槽位是否真的有数据
        if (!isSlotUsed(slot)) {
            throw new DbException("Slot is already empty");
        }
        // 取消头部标记并清空数据
        markSlotUsed(slot, false);
        tuples[slot] = null;
    }

    public void insertTuple(Tuple t) throws DbException {
        // 检查 TupleDesc 模式是否匹配
        if (!t.getTupleDesc().equals(this.td)) {
            throw new DbException("TupleDesc mismatch");
        }
        // 寻找空槽
        for (int i = 0; i < numSlots; i++) {
            if (!isSlotUsed(i)) {
                markSlotUsed(i, true);
                t.setRecordId(new RecordId(this.pid, i));
                tuples[i] = t;
                return;
            }
        }
        throw new DbException("HeapPage is full");
    }

    public void markDirty(boolean dirty, TransactionId tid) {
        this.isDirty = dirty;
        if (dirty) {
            this.dirtier = tid;
        } else {
            this.dirtier = null;
        }
    }

    public TransactionId isDirty() {
        return this.isDirty ? this.dirtier : null;
    }

    public int getNumEmptySlots() {
        int count = 0;
        for (int i = 0; i < numSlots; i++) {
            if (!isSlotUsed(i)) {
                count++;
            }
        }
        return count;
    }

    public boolean isSlotUsed(int i) {
        int byteIndex = i / 8;
        int bitIndex = i % 8;
        return (header[byteIndex] & (1 << bitIndex)) != 0;
    }

    private void markSlotUsed(int i, boolean value) {
        int byteIndex = i / 8;
        int bitIndex = i % 8;
        if (value) {
            header[byteIndex] |= (1 << bitIndex); // 置 1
        } else {
            header[byteIndex] &= ~(1 << bitIndex); // 置 0
        }
    }

    public Iterator<Tuple> iterator() {
        return new Iterator<Tuple>() {
            private int currentSlot = 0;

            @Override
            public boolean hasNext() {
                while (currentSlot < numSlots) {
                    if (isSlotUsed(currentSlot)) {
                        return true;
                    }
                    currentSlot++;
                }
                return false;
            }

            @Override
            public Tuple next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return tuples[currentSlot++];
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException();
            }
        };
    }
}
