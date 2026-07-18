package com.minidb.storage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * A heap file: an unordered collection of variable-length records, stored
 * across many fixed-size Pages. This is the classic "heap" storage
 * structure real databases use for tables that don't have a clustered
 * index (e.g. Postgres' default table storage).
 *
 * HeapFile is deliberately dumb about what's INSIDE a record -- it just
 * moves byte[] in and out. Row <-> byte[] (de)serialization is the job of
 * the executor/catalog layer above this, keeping storage decoupled from
 * schema (same separation Postgres draws between heapam and the catalog).
 */
public class HeapFile {

    private final PageManager pageManager;

    public HeapFile(PageManager pageManager) {
        this.pageManager = pageManager;
    }

    /**
     * Inserts a record, trying existing pages first (first-fit) before
     * allocating a new page. Returns the RID so the caller (or an index)
     * can find this exact record again later.
     */
    public RecordId insert(byte[] record) throws IOException {
        if (record.length + Page.SLOT_SIZE > Page.PAGE_SIZE - Page.PAGE_HEADER_SIZE) {
            throw new IllegalArgumentException("Record too large to fit in a single page");
        }

        for (int pageId = 0; pageId < pageManager.getPageCount(); pageId++) {
            Page page = pageManager.readPage(pageId);
            int slot = page.insertRecord(record);
            if (slot != -1) {
                pageManager.markDirty(pageId);
                return new RecordId(pageId, slot);
            }
        }

        // No existing page had room -- allocate a fresh one.
        Page newPage = pageManager.allocatePage();
        int slot = newPage.insertRecord(record);
        pageManager.markDirty(newPage.getPageId());
        return new RecordId(newPage.getPageId(), slot);
    }

    /** Reads back a single record by its RID, or null if it's been deleted. */
    public byte[] read(RecordId rid) throws IOException {
        Page page = pageManager.readPage(rid.getPageId());
        return page.readRecord(rid.getSlotIndex());
    }

    /** Deletes a record by RID. Returns false if it was already deleted / never existed. */
    public boolean delete(RecordId rid) throws IOException {
        Page page = pageManager.readPage(rid.getPageId());
        boolean removed = page.deleteRecord(rid.getSlotIndex());
        if (removed) {
            pageManager.markDirty(rid.getPageId());
        }
        return removed;
    }

    /**
     * Updates a record in place if it still fits its slot's original page
     * with room to spare via delete+reinsert; otherwise deletes the old
     * copy and inserts fresh (RID changes -- callers that maintain indexes
     * must handle this).
     */
    public RecordId update(RecordId rid, byte[] newRecord) throws IOException {
        delete(rid);
        return insert(newRecord);
    }

    /** Full table scan: iterates every live (non-deleted) record in the heap file, in physical order. */
    public Iterator<RecordEntry> scan() {
        return new HeapFileIterator();
    }

    /** A record plus the RID it was found at -- useful for scans that will later update/delete/index it. */
    public static final class RecordEntry {
        public final RecordId rid;
        public final byte[] data;

        public RecordEntry(RecordId rid, byte[] data) {
            this.rid = rid;
            this.data = data;
        }
    }

    private final class HeapFileIterator implements Iterator<RecordEntry> {
        private int currentPageId = 0;
        private int currentSlot = 0;
        private RecordEntry nextEntry = null;

        HeapFileIterator() {
            advance();
        }

        private void advance() {
            nextEntry = null;
            try {
                while (currentPageId < pageManager.getPageCount()) {
                    Page page = pageManager.readPage(currentPageId);
                    while (currentSlot < page.getSlotCount()) {
                        int slot = currentSlot++;
                        byte[] record = page.readRecord(slot);
                        if (record != null) {
                            nextEntry = new RecordEntry(new RecordId(currentPageId, slot), record);
                            return;
                        }
                    }
                    currentPageId++;
                    currentSlot = 0;
                }
            } catch (IOException e) {
                throw new RuntimeException("Error scanning heap file", e);
            }
        }

        @Override
        public boolean hasNext() {
            return nextEntry != null;
        }

        @Override
        public RecordEntry next() {
            if (nextEntry == null) {
                throw new NoSuchElementException();
            }
            RecordEntry result = nextEntry;
            advance();
            return result;
        }
    }

    /** Convenience: materializes the full scan into a List (fine for a mini-db, avoid for huge tables). */
    public List<RecordEntry> scanAll() {
        List<RecordEntry> results = new ArrayList<>();
        Iterator<RecordEntry> it = scan();
        while (it.hasNext()) {
            results.add(it.next());
        }
        return results;
    }
}
