# MiniDB

A relational database engine built from scratch in Java — page-based
storage, a hand-written SQL parser, a query executor, B+Tree indexes, and
basic ACID transactions. No JDBC driver wrapping another database, no
embedded SQLite underneath — every layer down to the disk page format is
implemented here.

Built as an embedded engine (like SQLite) with a clean separation between
the engine and its interface, so a network/server mode (like Postgres) can
be added later without touching the core.

## Why build this

Most CRUD apps use a database as a black box. This project builds the box,
to actually understand: how rows get packed into fixed-size disk pages, how
a SQL string becomes a query plan, how a B+Tree makes lookups fast, and how
a transaction guarantees atomicity even if the process crashes mid-write.

## Architecture

```
                        ┌─────────────────────┐
                        │   Interface Layer     │   MiniDBConnection API / CLI shell
                        │   (thin — swappable    │   (a TCP server can be added here later
                        │    for a TCP server)   │    without touching anything below)
                        └───────────┬───────────┘
                                    │  SQL text in, ResultSet out
                        ┌───────────▼───────────┐
                        │      SQL Parser        │   Tokenizer → Recursive-descent parser → AST
                        └───────────┬───────────┘
                                    │  AST
                        ┌───────────▼───────────┐
                        │    Query Executor      │   Table scans, filters, projections, joins
                        └──────┬─────────┬───────┘
                               │         │
                  ┌────────────▼───┐ ┌───▼─────────────┐
                  │ Catalog /       │ │  Index Manager   │   B+Tree indexes for fast lookups
                  │ Metadata Mgr    │ │                  │
                  └────────────┬───┘ └───┬─────────────┘
                                │         │
                        ┌───────▼─────────▼───────┐
                        │   Transaction Manager     │   BEGIN/COMMIT/ROLLBACK, WAL, locking
                        └───────────┬───────────────┘
                                    │
                        ┌───────────▼───────────┐
                        │    Storage Engine       │   Page format, PageManager (disk I/O +
                        │  (Page / HeapFile)      │   buffer pool), HeapFile (row storage)
                        └───────────┬───────────┘
                                    │
                               [ disk file ]
```

Each layer only talks to the layer directly below it. The executor never
touches a file handle; the storage engine has no idea what SQL is. This is
the same separation Postgres draws between its executor, access methods,
and storage manager — it's what makes each piece independently testable
and replaceable.

## Storage engine (implemented)

The foundation. Everything else in MiniDB will be built on top of this.

- **`Page`** — a fixed 4KB block, using a **slotted page** layout: a header,
  a slot directory that grows forward, and records that grow backward from
  the end of the page. This is the same layout Postgres and SQLite use —
  it lets records be variable-length and deleted/compacted without
  shifting every other record on the page.
- **`PageManager`** — the only class that touches `RandomAccessFile`/
  `FileChannel` directly. Reads and writes exactly `PAGE_SIZE` byte blocks
  at `pageId * PAGE_SIZE` offsets, and keeps a small LRU **buffer pool** in
  memory so hot pages aren't re-read from disk on every access (a tiny
  version of Postgres's `shared_buffers` or InnoDB's buffer pool).
- **`HeapFile`** — an unordered collection of records spread across many
  pages. Supports insert / read / update / delete / full scan, all
  addressed by `RecordId` (page + slot) — the same concept a B+Tree index
  will point to.

**Verified:** insert/read round-trips, records correctly span multiple
pages once a page fills up, delete + double-delete, update, scan
correctly skips deleted (tombstoned) records, and — critically — data
**survives closing and reopening the file**, proving it's genuinely
persisted to disk and not just held in memory.

## Roadmap

- [x] **Storage Engine** — page format, disk I/O, buffer pool, heap files
- [ ] **Catalog / Metadata Manager** — table schemas, column types, `CREATE TABLE`
- [ ] **SQL Parser** — tokenizer + recursive-descent parser → AST for
      `SELECT` / `INSERT` / `UPDATE` / `DELETE` / `CREATE TABLE`, with
      `WHERE`, `JOIN`, `ORDER BY`
- [ ] **Query Executor** — table scans, filters, projections, nested-loop joins
- [ ] **Index Manager** — B+Tree indexes, `CREATE INDEX`
- [ ] **Transaction Manager** — `BEGIN`/`COMMIT`/`ROLLBACK`, write-ahead log, locking
- [ ] **Interface Layer** — Java API + CLI shell (network/TCP mode is a
      future extension point, not part of the initial build)

## Building and running

Requires Java 17+ and Maven.

```bash
mvn compile        # compile
mvn test           # run unit tests
mvn package         # build runnable jar
```

## Design decisions worth calling out (interview talking points)

- **Slotted pages over fixed-offset rows** — lets variable-length records
  live on the same page and lets deletes be O(1) tombstones instead of
  requiring a page rewrite.
- **RID (page, slot) as the universal row address** — this is what makes
  indexes possible later: an index just maps a key to a RID instead of
  duplicating row data.
- **Buffer pool at the PageManager level, not the HeapFile level** — keeps
  caching a storage-engine concern; higher layers never know whether a
  page came from memory or disk.
- **Engine/interface split from day one** — the interface layer only ever
  sees "SQL string in, result out," so adding a TCP listener later is an
  additive change, not a rewrite.
