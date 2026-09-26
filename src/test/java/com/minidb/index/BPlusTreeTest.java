package com.minidb.index;

import com.minidb.storage.RecordId;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class BPlusTreeTest {

    @Test
    void insertAndSearchManyShuffledKeys() {
        BPlusTree<Integer> tree = new BPlusTree<>(4);
        Map<Integer, RecordId> expected = new LinkedHashMap<>();
        List<Integer> keys = new ArrayList<>();
        for (int i = 0; i < 200; i++) keys.add(i);
        Collections.shuffle(keys, new Random(42));

        for (int k : keys) {
            RecordId rid = new RecordId(k, 0);
            tree.insert(k, rid);
            expected.put(k, rid);
        }

        for (int k : keys) {
            List<RecordId> result = tree.search(k);
            assertEquals(1, result.size());
            assertEquals(expected.get(k), result.get(0));
        }
    }

    @Test
    void searchMissingKeyReturnsEmptyList() {
        BPlusTree<Integer> tree = new BPlusTree<>();
        tree.insert(1, new RecordId(1, 0));
        assertTrue(tree.search(9999).isEmpty());
    }

    @Test
    void rangeSearchReturnsExactBounds() {
        BPlusTree<Integer> tree = new BPlusTree<>(4);
        for (int i = 0; i < 100; i++) tree.insert(i, new RecordId(i, 0));
        assertEquals(10, tree.rangeSearch(50, 59).size());
    }

    @Test
    void duplicateKeysAccumulateMultipleRecordIds() {
        BPlusTree<String> tree = new BPlusTree<>();
        tree.insert("A", new RecordId(1, 0));
        tree.insert("A", new RecordId(2, 0));
        assertEquals(2, tree.search("A").size());
    }

    @Test
    void deleteRemovesKeyWithoutAffectingSiblings() {
        BPlusTree<Integer> tree = new BPlusTree<>(4);
        for (int i = 0; i < 200; i++) tree.insert(i, new RecordId(i, 0));

        tree.delete(100, new RecordId(100, 0));

        assertTrue(tree.search(100).isEmpty());
        assertEquals(1, tree.search(99).size());
        assertEquals(1, tree.search(101).size());
    }
}
