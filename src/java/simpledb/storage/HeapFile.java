package simpledb.storage;

import simpledb.common.Database;
import simpledb.common.DbException;
import simpledb.common.Permissions;
import simpledb.transaction.TransactionAbortedException;
import simpledb.transaction.TransactionId;

import java.io.*;
import java.util.*;

/**
 * HeapFile is an implementation of a DbFile that stores a collection of tuples
 * in no particular order.
 */
public class HeapFile implements DbFile {

    private final File file;
    private final TupleDesc td;

    public HeapFile(File f, TupleDesc td) {
        this.file = f;
        this.td = td;
    }

    public File getFile() {
        return this.file;
    }

    public int getId() {
        return file.getAbsoluteFile().hashCode();
    }

    public TupleDesc getTupleDesc() {
        return this.td;
    }

    public Page readPage(PageId pid) {
        try {
            RandomAccessFile raf = new RandomAccessFile(file, "r");
            int offset = pid.getPageNumber() * BufferPool.getPageSize();
            byte[] data = new byte[BufferPool.getPageSize()];

            raf.seek(offset);
            raf.read(data);
            raf.close();

            return new HeapPage((HeapPageId) pid, data);
        } catch (IOException e) {
            throw new IllegalArgumentException("Error reading page " + pid.getPageNumber() + " from file");
        }
    }

    public void writePage(Page page) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(file, "rw");
        int offset = page.getId().getPageNumber() * BufferPool.getPageSize();
        raf.seek(offset);
        raf.write(page.getPageData());
        raf.close();
    }

    public int numPages() {
        return (int) (file.length() / BufferPool.getPageSize());
    }

    public List<Page> insertTuple(TransactionId tid, Tuple t)
            throws DbException, IOException, TransactionAbortedException {
        ArrayList<Page> modifiedPages = new ArrayList<>();
        int i = 0;

        // 1. 尝试在现有的页中寻找有空槽的页
        for (; i < numPages(); i++) {
            HeapPageId pid = new HeapPageId(getId(), i);
            HeapPage page = (HeapPage) Database.getBufferPool().getPage(tid, pid, Permissions.READ_WRITE);
            if (page.getNumEmptySlots() > 0) {
                page.insertTuple(t);
                modifiedPages.add(page);
                return modifiedPages;
            }
        }

        // 2. 如果现有的所有页都满了，创建一个新页，追加到文件末尾
        HeapPageId newPid = new HeapPageId(getId(), i);
        HeapPage newPage = new HeapPage(newPid, HeapPage.createEmptyPageData());
        writePage(newPage); // 必须先写到磁盘，让 BufferPool 能读到

        // 3. 再次通过 BufferPool 获取这页，从而走正常的缓冲控制流程
        HeapPage page = (HeapPage) Database.getBufferPool().getPage(tid, newPid, Permissions.READ_WRITE);
        page.insertTuple(t);
        modifiedPages.add(page);

        return modifiedPages;
    }

    public ArrayList<Page> deleteTuple(TransactionId tid, Tuple t) throws DbException,
            TransactionAbortedException {
        ArrayList<Page> modifiedPages = new ArrayList<>();
        RecordId rid = t.getRecordId();

        // 校验所属文件
        if (rid == null || rid.getPageId().getTableId() != getId()) {
            throw new DbException("Tuple does not belong to this HeapFile");
        }

        // 通过 BufferPool 获取页面并执行删除
        HeapPage page = (HeapPage) Database.getBufferPool().getPage(tid, rid.getPageId(), Permissions.READ_WRITE);
        page.deleteTuple(t);
        modifiedPages.add(page);

        return modifiedPages;
    }

    public DbFileIterator iterator(TransactionId tid) {
        return new HeapFileIterator(this, tid);
    }

    private static class HeapFileIterator implements DbFileIterator {
        private final HeapFile heapFile;
        private final TransactionId tid;
        private Iterator<Tuple> tupleIterator;
        private int currentPageNo;
        private boolean isOpen;

        public HeapFileIterator(HeapFile heapFile, TransactionId tid) {
            this.heapFile = heapFile;
            this.tid = tid;
            this.isOpen = false;
        }

        @Override
        public void open() throws DbException, TransactionAbortedException {
            isOpen = true;
            currentPageNo = 0;
            tupleIterator = getPageTuples(currentPageNo);
        }

        private Iterator<Tuple> getPageTuples(int pageNo) throws TransactionAbortedException, DbException {
            if (pageNo >= heapFile.numPages()) {
                return null;
            }
            HeapPageId pid = new HeapPageId(heapFile.getId(), pageNo);
            HeapPage page = (HeapPage) Database.getBufferPool().getPage(tid, pid, Permissions.READ_ONLY);
            return page.iterator();
        }

        @Override
        public boolean hasNext() throws DbException, TransactionAbortedException {
            if (!isOpen) return false;

            if (tupleIterator != null && tupleIterator.hasNext()) {
                return true;
            }

            while (currentPageNo < heapFile.numPages() - 1) {
                currentPageNo++;
                tupleIterator = getPageTuples(currentPageNo);
                if (tupleIterator != null && tupleIterator.hasNext()) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public Tuple next() throws DbException, TransactionAbortedException, NoSuchElementException {
            if (!isOpen || !hasNext()) {
                throw new NoSuchElementException("No more tuples or iterator not opened");
            }
            return tupleIterator.next();
        }

        @Override
        public void rewind() throws DbException, TransactionAbortedException {
            close();
            open();
        }

        @Override
        public void close() {
            isOpen = false;
            tupleIterator = null;
            currentPageNo = 0;
        }
    }
}
