package com.minidb.index;

import com.minidb.storage.RecordId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A B+Tree index: keys point to a list of RecordIds (a list, not a single
 * RID, so a non-unique column can still be indexed — duplicate keys simply
 * accumulate multiple RIDs).
 *
 * This is a real B+Tree with node splitting on insert (not a wrapper
 * around TreeMap) — internal nodes hold only routing keys, all actual
 * key->value entries live in leaf nodes, and leaves are linked
 * (leaf.next) for efficient range scans left to right.
 *
 * Kept in-memory for now: rebuilt from a full table scan whenever
 * CREATE INDEX runs, and updated incrementally after that by the
 * IndexManager on every INSERT/UPDATE/DELETE. Persisting the tree's own
 * nodes to disk pages (the way Postgres/InnoDB do) is noted as future
 * work — it would reuse the exact same Page/PageManager this project
 * already has, just with a B+Tree node layout instead of a heap layout.
 *
 * Deletion is simplified: a key is removed from its leaf but underfull
 * nodes are never merged/rebalanced with siblings. The tree stays correct
 * (search and range scan still return exactly the right results) but can
 * become unbalanced in height distribution after heavy deletion — a
 * documented trade-off rather than an oversight.
 */
public class BPlusTree<K extends Comparable<K>> {

    private static final int DEFAULT_ORDER = 4; // max children per internal node

    private final int order;
    private Node<K> root;

    public BPlusTree() {
        this(DEFAULT_ORDER);
    }

    public BPlusTree(int order) {
        if (order < 3) throw new IllegalArgumentException("B+Tree order must be at least 3");
        this.order = order;
        this.root = new LeafNode<>();
    }

    // ---------- node types ----------

    private abstract static class Node<K extends Comparable<K>> {
        final List<K> keys = new ArrayList<>();
        abstract boolean isLeaf();
    }

    private static final class LeafNode<K extends Comparable<K>> extends Node<K> {
        final List<List<RecordId>> values = new ArrayList<>();
        LeafNode<K> next;
        @Override boolean isLeaf() { return true; }
    }

    private static final class InternalNode<K extends Comparable<K>> extends Node<K> {
        final List<Node<K>> children = new ArrayList<>();
        @Override boolean isLeaf() { return false; }
    }

    private static final class SplitResult<K extends Comparable<K>> {
        final K splitKey;
        final Node<K> newNode;
        SplitResult(K splitKey, Node<K> newNode) { this.splitKey = splitKey; this.newNode = newNode; }
    }

    // ---------- insert ----------

    public void insert(K key, RecordId rid) {
        SplitResult<K> result = insertInto(root, key, rid);
        if (result != null) {
            InternalNode<K> newRoot = new InternalNode<>();
            newRoot.keys.add(result.splitKey);
            newRoot.children.add(root);
            newRoot.children.add(result.newNode);
            root = newRoot;
        }
    }

    @SuppressWarnings("unchecked")
    private SplitResult<K> insertInto(Node<K> node, K key, RecordId rid) {
        if (node.isLeaf()) {
            LeafNode<K> leaf = (LeafNode<K>) node;
            int idx = Collections.binarySearch(leaf.keys, key);
            if (idx >= 0) {
                leaf.values.get(idx).add(rid); // duplicate key: just add another RID
                return null;
            }
            int insertPos = -(idx + 1);
            leaf.keys.add(insertPos, key);
            List<RecordId> rids = new ArrayList<>();
            rids.add(rid);
            leaf.values.add(insertPos, rids);

            if (leaf.keys.size() < order) return null;
            return splitLeaf(leaf);
        } else {
            InternalNode<K> internal = (InternalNode<K>) node;
            int childIdx = findChildIndex(internal, key);
            SplitResult<K> childSplit = insertInto(internal.children.get(childIdx), key, rid);
            if (childSplit == null) return null;

            internal.keys.add(childIdx, childSplit.splitKey);
            internal.children.add(childIdx + 1, childSplit.newNode);

            if (internal.keys.size() < order) return null;
            return splitInternal(internal);
        }
    }

    private SplitResult<K> splitLeaf(LeafNode<K> leaf) {
        int mid = leaf.keys.size() / 2;
        LeafNode<K> right = new LeafNode<>();
        right.keys.addAll(leaf.keys.subList(mid, leaf.keys.size()));
        right.values.addAll(leaf.values.subList(mid, leaf.values.size()));
        leaf.keys.subList(mid, leaf.keys.size()).clear();
        leaf.values.subList(mid, leaf.values.size()).clear();

        right.next = leaf.next;
        leaf.next = right;

        return new SplitResult<>(right.keys.get(0), right);
    }

    private SplitResult<K> splitInternal(InternalNode<K> internal) {
        int mid = internal.keys.size() / 2;
        K splitKey = internal.keys.get(mid);

        InternalNode<K> right = new InternalNode<>();
        right.keys.addAll(internal.keys.subList(mid + 1, internal.keys.size()));
        right.children.addAll(internal.children.subList(mid + 1, internal.children.size()));

        internal.keys.subList(mid, internal.keys.size()).clear();
        internal.children.subList(mid + 1, internal.children.size()).clear();

        return new SplitResult<>(splitKey, right);
    }

    /** children[i] holds keys < keys[i]; the last child holds keys >= the last routing key. */
    private int findChildIndex(InternalNode<K> internal, K key) {
        int idx = 0;
        while (idx < internal.keys.size() && key.compareTo(internal.keys.get(idx)) >= 0) {
            idx++;
        }
        return idx;
    }

    // ---------- search ----------

    /** Exact-match lookup. Returns an empty list (never null) if the key isn't present. */
    @SuppressWarnings("unchecked")
    public List<RecordId> search(K key) {
        LeafNode<K> leaf = findLeaf(key);
        int idx = Collections.binarySearch(leaf.keys, key);
        return idx >= 0 ? new ArrayList<>(leaf.values.get(idx)) : Collections.emptyList();
    }

    /** Inclusive range scan [low, high], in ascending key order. */
    public List<RecordId> rangeSearch(K low, K high) {
        List<RecordId> results = new ArrayList<>();
        LeafNode<K> leaf = findLeaf(low);
        while (leaf != null) {
            for (int i = 0; i < leaf.keys.size(); i++) {
                K k = leaf.keys.get(i);
                if (k.compareTo(low) < 0) continue;
                if (k.compareTo(high) > 0) return results;
                results.addAll(leaf.values.get(i));
            }
            leaf = leaf.next;
        }
        return results;
    }

    @SuppressWarnings("unchecked")
    private LeafNode<K> findLeaf(K key) {
        Node<K> node = root;
        while (!node.isLeaf()) {
            InternalNode<K> internal = (InternalNode<K>) node;
            node = internal.children.get(findChildIndex(internal, key));
        }
        return (LeafNode<K>) node;
    }

    // ---------- delete ----------

    /** Removes one (key, rid) association. See class javadoc re: no node merging/rebalancing. */
    public void delete(K key, RecordId rid) {
        LeafNode<K> leaf = findLeaf(key);
        int idx = Collections.binarySearch(leaf.keys, key);
        if (idx < 0) return;
        List<RecordId> rids = leaf.values.get(idx);
        rids.remove(rid);
        if (rids.isEmpty()) {
            leaf.keys.remove(idx);
            leaf.values.remove(idx);
        }
    }
}
