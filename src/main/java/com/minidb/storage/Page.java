package com.minidb.storage;

import java.nio.ByteBuffer;

/**
 * A fixed-size unit of storage backed by a raw byte array.
 *
 * MiniDB, like real database engines (Postgres, SQLite, MySQL/InnoDB),
 * never reads or writes individual rows to disk directly. Instead, disk I/O
 * always happens in fixed-size blocks called pages. This gives us:
 *   - Predictable I/O cost (one disk read = one page, regardless of row size)
 *   - A natural unit for caching (see BufferPool)
 *   - A natural unit for locking/concurrency control (future work)
 *
 * Page layout (slotted page design, same idea Postgres/SQLite use):
 *
 *   [ PAGE HEADER ][ SLOT DIRECTORY -> ][          free space          ][ <- RECORDS ]
 *
 *   Header (fixed, PAGE_HEADER_SIZE bytes):
 *     bytes 0-3   : pageId (int)
 *     bytes 4-7   : slotCount (int) - number of slots in the slot directory
 *     bytes 8-11  : freeSpacePointer (int) - offset where the next record is written
 *                   (records are appended from the END of the page, growing backward)
 *
 *   Slot directory (grows forward from the header, one entry per record):
 *     each slot = 8 bytes: [ offset (int) ][ length (int) ]
 *     a slot with length == -1 means the record was deleted (tombstone)
 *
 *   Records grow backward from the end of the page toward the slot directory.
 *   A page is full when the slot directory would collide with the record area.
 */
public class Page {

    public static final int PAGE_SIZE = 4096; // 4 KB, same default as Postgres/InnoDB
    public static final int PAGE_HEADER_SIZE = 12;
    public static final int SLOT_SIZE = 8;
    public static final int TOMBSTONE = -1;

    private final byte[] data;
    private final int pageId;

    /** Wraps raw bytes read from disk into a Page. */
    public Page(int pageId, byte[] data) {
        if (data.length != PAGE_SIZE) {
            throw new IllegalArgumentException("Page data must be exactly " + PAGE_SIZE + " bytes");
        }
        this.pageId = pageId;
        this.data = data;
    }

    /** Creates a brand-new, empty page. */
    public static Page createEmpty(int pageId) {
        byte[] data = new byte[PAGE_SIZE];
        Page page = new Page(pageId, data);
        page.writeHeader(0, PAGE_SIZE); // no slots yet, free space pointer at the very end
        return page;
    }

    // ---------- header helpers ----------

    private void writeHeader(int slotCount, int freeSpacePointer) {
        ByteBuffer buf = ByteBuffer.wrap(data);
        buf.putInt(0, pageId);
        buf.putInt(4, slotCount);
        buf.putInt(8, freeSpacePointer);
    }

    public int getSlotCount() {
        return ByteBuffer.wrap(data).getInt(4);
    }

    private void setSlotCount(int count) {
        ByteBuffer.wrap(data).putInt(4, count);
    }

    public int getFreeSpacePointer() {
        return ByteBuffer.wrap(data).getInt(8);
    }

    private void setFreeSpacePointer(int ptr) {
        ByteBuffer.wrap(data).putInt(8, ptr);
    }

    public int getPageId() {
        return pageId;
    }

    // ---------- slot directory helpers ----------

    private int slotOffset(int slotIndex) {
        return PAGE_HEADER_SIZE + slotIndex * SLOT_SIZE;
    }

    private int getSlotRecordOffset(int slotIndex) {
        return ByteBuffer.wrap(data).getInt(slotOffset(slotIndex));
    }

    private int getSlotRecordLength(int slotIndex) {
        return ByteBuffer.wrap(data).getInt(slotOffset(slotIndex) + 4);
    }

    private void setSlot(int slotIndex, int recordOffset, int recordLength) {
        ByteBuffer buf = ByteBuffer.wrap(data);
        buf.putInt(slotOffset(slotIndex), recordOffset);
        buf.putInt(slotOffset(slotIndex) + 4, recordLength);
    }

    /** Bytes currently free between the slot directory and the record area. */
    public int freeSpace() {
        int slotDirectoryEnd = PAGE_HEADER_SIZE + getSlotCount() * SLOT_SIZE;
        return getFreeSpacePointer() - slotDirectoryEnd;
    }

    /**
     * Inserts a record into this page.
     * @return the slot index (used as part of the record's RID), or -1 if there's no room.
     */
    public int insertRecord(byte[] record) {
        int needed = record.length + SLOT_SIZE;
        if (needed > freeSpace()) {
            return -1; // caller should try the next page / allocate a new one
        }

        int newFreeSpacePointer = getFreeSpacePointer() - record.length;
        System.arraycopy(record, 0, data, newFreeSpacePointer, record.length);

        int slotIndex = getSlotCount();
        setSlot(slotIndex, newFreeSpacePointer, record.length);
        setSlotCount(slotIndex + 1);
        setFreeSpacePointer(newFreeSpacePointer);

        return slotIndex;
    }

    /** Reads back the record stored at the given slot, or null if deleted/invalid. */
    public byte[] readRecord(int slotIndex) {
        if (slotIndex < 0 || slotIndex >= getSlotCount()) {
            return null;
        }
        int length = getSlotRecordLength(slotIndex);
        if (length == TOMBSTONE) {
            return null;
        }
        int offset = getSlotRecordOffset(slotIndex);
        byte[] record = new byte[length];
        System.arraycopy(data, offset, record, 0, length);
        return record;
    }

    /** Marks a slot as deleted (tombstone). The bytes are reclaimed on next compaction. */
    public boolean deleteRecord(int slotIndex) {
        if (slotIndex < 0 || slotIndex >= getSlotCount()) {
            return false;
        }
        if (getSlotRecordLength(slotIndex) == TOMBSTONE) {
            return false; // already deleted
        }
        int offset = getSlotRecordOffset(slotIndex);
        ByteBuffer.wrap(data).putInt(slotOffset(slotIndex) + 4, TOMBSTONE);
        // offset left intact for debugging; length TOMBSTONE marks it dead
        return offset >= 0;
    }

    public byte[] getRawData() {
        return data;
    }
}
