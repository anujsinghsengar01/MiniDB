package com.minidb.storage;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Owns the on-disk file for a table and is the ONLY place that talks to
 * java.io/nio directly. Everything above this layer (HeapFile, executor,
 * etc.) works purely in terms of pageId -> Page and never touches a file
 * handle or an offset.
 *
 * Includes a small LRU buffer pool so repeated access to hot pages doesn't
 * hit disk every time -- this mirrors the buffer pool every real RDBMS has
 * (Postgres shared_buffers, InnoDB buffer pool, etc.), just much simpler.
 *
 * File layout on disk: page 0, page 1, page 2, ... back to back, each
 * exactly Page.PAGE_SIZE bytes. pageId N lives at byte offset N * PAGE_SIZE.
 */
public class PageManager implements AutoCloseable {

    private static final int BUFFER_POOL_CAPACITY = 64; // pages kept in memory

    private final RandomAccessFile file;
    private final FileChannel channel;
    private int pageCount;

    // Simple LRU cache: LinkedHashMap in access order, evict eldest when over capacity.
    private final LinkedHashMap<Integer, Page> bufferPool;
    // Pages that were modified since being read/created and haven't been flushed yet.
    private final Map<Integer, Boolean> dirtyPages = new LinkedHashMap<>();

    public PageManager(String filePath) throws IOException {
        this.file = new RandomAccessFile(filePath, "rw");
        this.channel = file.getChannel();
        this.pageCount = (int) (file.length() / Page.PAGE_SIZE);

        this.bufferPool = new LinkedHashMap<>(BUFFER_POOL_CAPACITY, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, Page> eldest) {
                if (size() > BUFFER_POOL_CAPACITY) {
                    flushIfDirty(eldest.getKey(), eldest.getValue());
                    return true;
                }
                return false;
            }
        };
    }

    /** Total number of pages currently allocated in this file. */
    public synchronized int getPageCount() {
        return pageCount;
    }

    /** Allocates a brand-new page at the end of the file and returns it (not yet flushed). */
    public synchronized Page allocatePage() {
        int newPageId = pageCount++;
        Page page = Page.createEmpty(newPageId);
        bufferPool.put(newPageId, page);
        dirtyPages.put(newPageId, true);
        return page;
    }

    /** Reads a page, going to the buffer pool first and disk on a miss. */
    public synchronized Page readPage(int pageId) throws IOException {
        if (pageId < 0 || pageId >= pageCount) {
            throw new IllegalArgumentException("Invalid pageId " + pageId + " (pageCount=" + pageCount + ")");
        }
        Page cached = bufferPool.get(pageId);
        if (cached != null) {
            return cached;
        }
        byte[] raw = new byte[Page.PAGE_SIZE];
        long offset = (long) pageId * Page.PAGE_SIZE;
        channel.read(java.nio.ByteBuffer.wrap(raw), offset);
        Page page = new Page(pageId, raw);
        bufferPool.put(pageId, page);
        return page;
    }

    /** Marks a page as modified. Must be called after mutating a Page returned by readPage/allocatePage. */
    public synchronized void markDirty(int pageId) {
        dirtyPages.put(pageId, true);
    }

    private void flushIfDirty(int pageId, Page page) {
        if (!Boolean.TRUE.equals(dirtyPages.remove(pageId))) {
            return;
        }
        try {
            long offset = (long) pageId * Page.PAGE_SIZE;
            channel.write(java.nio.ByteBuffer.wrap(page.getRawData()), offset);
        } catch (IOException e) {
            throw new RuntimeException("Failed to flush page " + pageId, e);
        }
    }

    /** Flushes every dirty page to disk. Call at commit time / shutdown. */
    public synchronized void flushAll() {
        for (Map.Entry<Integer, Page> entry : bufferPool.entrySet()) {
            flushIfDirty(entry.getKey(), entry.getValue());
        }
        try {
            channel.force(true);
        } catch (IOException e) {
            throw new RuntimeException("Failed to fsync file", e);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        flushAll();
        channel.close();
        file.close();
    }
}
