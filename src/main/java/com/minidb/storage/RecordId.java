package com.minidb.storage;

import java.util.Objects;

/**
 * A Record ID (RID) - the physical address of a row: which page it lives on
 * and which slot within that page. This is exactly what index entries point
 * to (see the future B+Tree index), and what an UPDATE/DELETE uses to find
 * a row without re-scanning the whole table.
 */
public final class RecordId {
    private final int pageId;
    private final int slotIndex;

    public RecordId(int pageId, int slotIndex) {
        this.pageId = pageId;
        this.slotIndex = slotIndex;
    }

    public int getPageId() {
        return pageId;
    }

    public int getSlotIndex() {
        return slotIndex;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RecordId)) return false;
        RecordId other = (RecordId) o;
        return pageId == other.pageId && slotIndex == other.slotIndex;
    }

    @Override
    public int hashCode() {
        return Objects.hash(pageId, slotIndex);
    }

    @Override
    public String toString() {
        return "RID(page=" + pageId + ", slot=" + slotIndex + ")";
    }
}
