package simpledb.storage;

import simpledb.common.Database;
import simpledb.common.DbException;
import simpledb.common.Permissions;
import simpledb.transaction.TransactionAbortedException;
import simpledb.transaction.TransactionId;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

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
    // 使用并发队列来实现 LRU (Least Recently Used) 淘汰策略
    private final ConcurrentLinkedQueue<PageId> lruQueue;

    public BufferPool(int numPages) {
        this.numPages = numPages;
        this.pageStore = new ConcurrentHashMap<>();
        this.lruQueue = new ConcurrentLinkedQueue<>();
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

    public Page getPage(TransactionId tid, PageId pid, Permissions perm)
            throws TransactionAbortedException, DbException {
        if (!pageStore.containsKey(pid)) {
            // 如果缓冲池满了，执行淘汰策略
            if (pageStore.size() >= numPages) {
                evictPage();
            }
            DbFile dbFile = Database.getCatalog().getDatabaseFile(pid.getTableId());
            Page page = dbFile.readPage(pid);
            pageStore.put(pid, page);
        }

        // 更新 LRU 队列：将最近访问的页移到队尾
        lruQueue.remove(pid);
        lruQueue.add(pid);

        return pageStore.get(pid);
    }

    public void unsafeReleasePage(TransactionId tid, PageId pid) { }

    public void transactionComplete(TransactionId tid) { }

    public boolean holdsLock(TransactionId tid, PageId p) { return false; }

    public void transactionComplete(TransactionId tid, boolean commit) { }

    public void insertTuple(TransactionId tid, int tableId, Tuple t)
            throws DbException, IOException, TransactionAbortedException {
        // 从 Catalog 查找到表，调用底层的 HeapFile 进行插入
        DbFile file = Database.getCatalog().getDatabaseFile(tableId);
        List<Page> modifiedPages = file.insertTuple(tid, t);

        for (Page page : modifiedPages) {
            page.markDirty(true, tid);
            PageId pid = page.getId();
            // 确保新页进入缓冲池及 LRU 队列
            if (!pageStore.containsKey(pid)) {
                if (pageStore.size() >= numPages) {
                    evictPage();
                }
                pageStore.put(pid, page);
            }
            lruQueue.remove(pid);
            lruQueue.add(pid);
        }
    }

    public void deleteTuple(TransactionId tid, Tuple t)
            throws DbException, IOException, TransactionAbortedException {
        int tableId = t.getRecordId().getPageId().getTableId();
        DbFile file = Database.getCatalog().getDatabaseFile(tableId);
        List<Page> modifiedPages = file.deleteTuple(tid, t);

        for (Page page : modifiedPages) {
            page.markDirty(true, tid);
            PageId pid = page.getId();
            // 确保修改的页存在于缓冲池及 LRU 队列
            if (!pageStore.containsKey(pid)) {
                if (pageStore.size() >= numPages) {
                    evictPage();
                }
                pageStore.put(pid, page);
            }
            lruQueue.remove(pid);
            lruQueue.add(pid);
        }
    }

    public synchronized void flushAllPages() throws IOException {
        for (PageId pid : pageStore.keySet()) {
            flushPage(pid);
        }
    }

    public synchronized void discardPage(PageId pid) {
        pageStore.remove(pid);
        lruQueue.remove(pid);
    }

    private synchronized void flushPage(PageId pid) throws IOException {
        Page page = pageStore.get(pid);
        // 只有当页存在且被标记为脏页时，才将其刷入磁盘并清除脏标记
        if (page != null && page.isDirty() != null) {
            DbFile file = Database.getCatalog().getDatabaseFile(pid.getTableId());
            file.writePage(page);
            page.markDirty(false, null);
        }
    }

    public synchronized void flushPages(TransactionId tid) throws IOException { }

    private synchronized void evictPage() throws DbException {
        // 获取队首元素（最久未使用的页）作为淘汰候选
        PageId victim = lruQueue.peek();
        if (victim == null) {
            throw new DbException("BufferPool is empty, nothing to evict.");
        }
        try {
            // 如果该页是脏页，将其刷入磁盘
            flushPage(victim);
        } catch (IOException e) {
            throw new DbException("Failed to flush page during eviction: " + e.getMessage());
        }
        // 将其从缓冲池和 LRU 队列中彻底移除
        discardPage(victim);
    }
}
