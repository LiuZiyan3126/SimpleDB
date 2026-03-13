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
 * in no particular order. Tuples are stored on pages, each of which is a fixed
 * size, and the file is simply a collection of those pages. HeapFile works
 * closely with HeapPage. The format of HeapPages is described in the HeapPage
 * constructor.
 * * @see HeapPage#HeapPage
 * @author Sam Madden
 */
public class HeapFile implements DbFile {

    private final File file;
    private final TupleDesc td;

    /**
     * Constructs a heap file backed by the specified file.
     * * @param f
     * the file that stores the on-disk backing store for this heap
     * file.
     */
    public HeapFile(File f, TupleDesc td) {
        this.file = f;
        this.td = td;
    }

    /**
     * Returns the File backing this HeapFile on disk.
     * * @return the File backing this HeapFile on disk.
     */
    public File getFile() {
        return this.file;
    }

    /**
     * Returns an ID uniquely identifying this HeapFile. Implementation note:
     * you will need to generate this tableid somewhere to ensure that each
     * HeapFile has a "unique id," and that you always return the same value for
     * a particular HeapFile. We suggest hashing the absolute file name of the
     * file underlying the heapfile, i.e. f.getAbsoluteFile().hashCode().
     * * @return an ID uniquely identifying this HeapFile.
     */
    public int getId() {
        return file.getAbsoluteFile().hashCode();
    }

    /**
     * Returns the TupleDesc of the table stored in this DbFile.
     * * @return TupleDesc of this DbFile.
     */
    public TupleDesc getTupleDesc() {
        return this.td;
    }

    // see DbFile.java for javadocs
    public Page readPage(PageId pid) {
        try {
            RandomAccessFile raf = new RandomAccessFile(file, "r");
            // 计算偏移量：页号 * 页大小
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

    // see DbFile.java for javadocs
    public void writePage(Page page) throws IOException {
        // some code goes here
        // not necessary for lab1
    }

    /**
     * Returns the number of pages in this HeapFile.
     */
    public int numPages() {
        // 文件总大小 / 页面大小
        return (int) (file.length() / BufferPool.getPageSize());
    }

    // see DbFile.java for javadocs
    public List<Page> insertTuple(TransactionId tid, Tuple t)
            throws DbException, IOException, TransactionAbortedException {
        // some code goes here
        return null;
        // not necessary for lab1
    }

    // see DbFile.java for javadocs
    public ArrayList<Page> deleteTuple(TransactionId tid, Tuple t) throws DbException,
            TransactionAbortedException {
        // some code goes here
        return null;
        // not necessary for lab1
    }

    // see DbFile.java for javadocs
    public DbFileIterator iterator(TransactionId tid) {
        return new HeapFileIterator(this, tid);
    }

    /**
     * Helper class to implement the DbFileIterator for HeapFile.
     */
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

        // 核心难点：跨页获取 Tuple，必须通过 BufferPool 获取 Page
        private Iterator<Tuple> getPageTuples(int pageNo) throws TransactionAbortedException, DbException {
            if (pageNo >= heapFile.numPages()) {
                return null;
            }
            HeapPageId pid = new HeapPageId(heapFile.getId(), pageNo);
            // 必须使用 Database.getBufferPool().getPage()，这是并发控制和缓存的关键
            HeapPage page = (HeapPage) Database.getBufferPool().getPage(tid, pid, Permissions.READ_ONLY);
            return page.iterator();
        }

        @Override
        public boolean hasNext() throws DbException, TransactionAbortedException {
            if (!isOpen) return false;

            // 当前页的迭代器没遍历完，直接返回 true
            if (tupleIterator != null && tupleIterator.hasNext()) {
                return true;
            }

            // 当前页遍历完了，尝试去下一页找，直到找到有 Tuple 的页或者遍历完所有页
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
