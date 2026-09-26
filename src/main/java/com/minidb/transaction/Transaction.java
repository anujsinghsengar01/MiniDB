package com.minidb.transaction;

import java.util.ArrayList;
import java.util.List;

/** A single in-flight transaction: its id and the undo log accumulated so far. */
public final class Transaction {
    public final int id;
    public final List<UndoEntry> undoLog = new ArrayList<>();

    public Transaction(int id) {
        this.id = id;
    }
}
