package simpledb.storage;

import simpledb.common.Database;
import simpledb.common.DbException;
import simpledb.common.Permissions;
import simpledb.transaction.TransactionAbortedException;
import simpledb.transaction.TransactionId;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BufferPool manages the reading and writing of pages into memory from
 * disk.
 */
public class BufferPool {
    private static final int DEFAULT_PAGE_SIZE = 4096;
    private static int pageSize = DEFAULT_PAGE_SIZE;
    public static final int DEFAULT_PAGES = 50;

    private final int numPages;
    private final ConcurrentHashMap<PageId, Page> pageStore;

    public BufferPool(int numPages) {
        this.numPages = numPages;
        this.pageStore = new ConcurrentHashMap<>();
    }

    public static int getPageSize() {
        return pageSize;
    }

    public static void setPageSize(int pageSize) {
        BufferPool.pageSize = pageSize;
    }

    public static void resetPageSize() {
        BufferPool.pageSize = DEFAULT_PAGE_SIZE;
    }

    public  Page getPage(TransactionId tid, PageId pid, Permissions perm)
            throws TransactionAbortedException, DbException {
        if (!pageStore.containsKey(pid)) {
            if (pageStore.size() >= numPages) {
                throw new DbException("BufferPool is full!");
            }
            DbFile dbFile = Database.getCatalog().getDatabaseFile(pid.getTableId());
            Page page = dbFile.readPage(pid);
            pageStore.put(pid, page);
        }
        return pageStore.get(pid);
    }

    public  void unsafeReleasePage(TransactionId tid, PageId pid) { }

    public void transactionComplete(TransactionId tid) { }

    public boolean holdsLock(TransactionId tid, PageId p) { return false; }

    public void transactionComplete(TransactionId tid, boolean commit) { }

    public void insertTuple(TransactionId tid, int tableId, Tuple t)
            throws DbException, IOException, TransactionAbortedException {
        // 从 Catalog 查找到表，调用底层的 HeapFile 进行插入
        DbFile file = Database.getCatalog().getDatabaseFile(tableId);
        List<Page> modifiedPages = file.insertTuple(tid, t);

        // 更新缓存中的页，并将其标记为 Dirty
        for (Page page : modifiedPages) {
            page.markDirty(true, tid);
            pageStore.put(page.getId(), page);
        }
    }

    public  void deleteTuple(TransactionId tid, Tuple t)
            throws DbException, IOException, TransactionAbortedException {
        int tableId = t.getRecordId().getPageId().getTableId();
        DbFile file = Database.getCatalog().getDatabaseFile(tableId);
        List<Page> modifiedPages = file.deleteTuple(tid, t);

        // 标记并更新对应的脏页
        for (Page page : modifiedPages) {
            page.markDirty(true, tid);
        }
    }

    public synchronized void flushAllPages() throws IOException { }

    public synchronized void discardPage(PageId pid) { }

    private synchronized  void flushPage(PageId pid) throws IOException { }

    public synchronized  void flushPages(TransactionId tid) throws IOException { }

    private synchronized  void evictPage() throws DbException { }
}
