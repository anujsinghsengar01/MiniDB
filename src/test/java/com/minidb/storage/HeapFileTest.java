package com.minidb.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HeapFileTest {

    @TempDir
    Path tempDir;

    private PageManager pageManager;
    private HeapFile heapFile;
    private String dbFilePath;

    @BeforeEach
    void setUp() throws IOException {
        dbFilePath = tempDir.resolve("test.tbl").toString();
        pageManager = new PageManager(dbFilePath);
        heapFile = new HeapFile(pageManager);
    }

    @AfterEach
    void tearDown() throws IOException {
        pageManager.close();
    }

    private byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private String str(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    @Test
    void insertAndReadSingleRecord() throws IOException {
        RecordId rid = heapFile.insert(bytes("hello minidb"));
        byte[] readBack = heapFile.read(rid);
        assertEquals("hello minidb", str(readBack));
    }

    @Test
    void insertManyRecordsSpanningMultiplePages() throws IOException {
        int n = 500; // enough to force several pages at PAGE_SIZE=4096
        RecordId[] rids = new RecordId[n];
        for (int i = 0; i < n; i++) {
            rids[i] = heapFile.insert(bytes("row-number-" + i));
        }
        assertTrue(pageManager.getPageCount() > 1, "expected records to span multiple pages");
        for (int i = 0; i < n; i++) {
            assertEquals("row-number-" + i, str(heapFile.read(rids[i])));
        }
    }

    @Test
    void deleteRemovesRecord() throws IOException {
        RecordId rid = heapFile.insert(bytes("to be deleted"));
        assertTrue(heapFile.delete(rid));
        assertNull(heapFile.read(rid));
        assertFalse(heapFile.delete(rid), "double delete should return false");
    }

    @Test
    void updateChangesContent() throws IOException {
        RecordId rid = heapFile.insert(bytes("old value"));
        RecordId newRid = heapFile.update(rid, bytes("new value"));
        assertEquals("new value", str(heapFile.read(newRid)));
    }

    @Test
    void scanReturnsAllLiveRecordsAndSkipsDeleted() throws IOException {
        RecordId r1 = heapFile.insert(bytes("alpha"));
        heapFile.insert(bytes("beta"));
        heapFile.insert(bytes("gamma"));
        heapFile.delete(r1);

        List<HeapFile.RecordEntry> all = heapFile.scanAll();
        assertEquals(2, all.size());
        assertTrue(all.stream().anyMatch(e -> str(e.data).equals("beta")));
        assertTrue(all.stream().anyMatch(e -> str(e.data).equals("gamma")));
    }

    @Test
    void dataSurvivesReopeningTheFile() throws IOException {
        RecordId rid = heapFile.insert(bytes("persisted across restarts"));
        pageManager.close(); // simulates process shutdown, flushes to disk

        PageManager reopened = new PageManager(dbFilePath);
        HeapFile reopenedHeap = new HeapFile(reopened);
        assertEquals("persisted across restarts", str(reopenedHeap.read(rid)));
        reopened.close();
    }
}
