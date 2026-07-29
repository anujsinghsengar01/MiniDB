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

## Catalog / metadata manager (implemented)

Tracks what tables exist and what they look like.

- **`ColumnType`** — the supported column types: `INT`, `DOUBLE`, `VARCHAR`, `BOOLEAN`.
- **`Column`** — one column's name, type, VARCHAR length, and constraints
  (`PRIMARY KEY`, nullability). Immutable by design.
- **`TableSchema`** — a table's name + ordered column list, with binary
  `serialize()`/`deserialize()` so it can be stored as plain bytes.
- **`Catalog`** — the piece that ties it together:
  - Persists every table's schema as a row in its own system table
    (`catalog.sys`), using the *same* `HeapFile` storage engine that
    stores regular data — MiniDB's metadata is just rows in a table,
    exactly like Postgres's `pg_catalog`.
  - Gives each table its own on-disk data file (`<table>.tbl`) and its
    own `HeapFile`, opened lazily and cached.
  - Handles `CREATE TABLE` / `DROP TABLE`, table/column lookups, and
    closing/flushing everything cleanly.

**Verified:** schema round-trips through serialization, duplicate
`CREATE TABLE` is rejected, column lookups (including primary key
detection) work, two tables get fully independent data files, and —
same persistence bar as the storage engine — **both schemas and their
row data survive closing and reopening the catalog**.

## SQL Parser (implemented)

Turns SQL text into an AST the executor can walk. Hand-written — no
ANTLR or parser-generator dependency — so there's no build-time code
generation to explain away and every grammar rule is visible in the
source.

- **`Lexer`** — tokenizer. Handles keywords, identifiers, int/double/string
  literals (with `''`-escaped quotes), operators (`= <> != < <= > >=`),
  and `--` line comments.
- **`Parser`** — recursive-descent parser, one method per grammar rule
  (`parseSelect`, `parseInsert`, `parseWhere`, ...). Supports:
  - `SELECT` (with column list or `*`, a single `JOIN ... ON`, `WHERE`
    with `AND`/`OR` and comparisons, `ORDER BY ... ASC|DESC`)
  - `INSERT INTO ... VALUES` (positional or with an explicit column list)
  - `UPDATE ... SET ... WHERE`
  - `DELETE FROM ... WHERE`
  - `CREATE TABLE` (reuses `catalog.Column` directly — the parser
    produces exactly the object the Catalog needs, no translation step)
  - `DROP TABLE`
- **`ast/`** — the AST node types (`SelectStatement`, `InsertStatement`,
  `Expression.BinaryExpression`, etc.) that the executor will walk in the
  next phase.

**Verified:** all six statement types parse correctly, including JOINs,
ORDER BY with DESC, positional vs. explicit-column INSERT, escaped
string literals, NOT NULL/PRIMARY KEY constraints, and that malformed
SQL raises a clear `ParseException` instead of silently misparsing.

## Query Executor (implemented)

Where SQL actually starts doing something — walks the AST and runs it
against the Catalog and HeapFiles.

- **`RowSerializer`** — converts a typed row (`Object[]`) to and from the
  `byte[]` a `HeapFile` stores, and coerces parsed literals into a
  column's exact type (an `INT` literal into a `DOUBLE` column becomes a
  `Double`), enforcing `NOT NULL`/`VARCHAR` length along the way.
- **`RowContext`** + **`ExpressionEvaluator`** — resolve column references
  (qualified or not) and evaluate `WHERE`/`JOIN...ON` expressions against
  a row, or a pair of rows when a join is involved.
- **`Executor`** — one method per statement type:
  - `CREATE TABLE` / `DROP TABLE` → straight to the Catalog
  - `INSERT` → coerces + validates values, checks `PRIMARY KEY`
    uniqueness (currently a full scan — the Index phase will make this
    O(log n)), serializes, writes
  - `SELECT` → scan → optional nested-loop `JOIN` → filter (`WHERE`) →
    sort (`ORDER BY`) → project (column list or `*`)
  - `UPDATE` / `DELETE` → matches are materialized before mutating, so
    the executor never mutates a `HeapFile` while scanning it

**Verified:** the full pipeline end-to-end — SQL text → parse → execute
→ real bytes on disk — across `CREATE TABLE`, positional and named
`INSERT`, duplicate-`PRIMARY KEY` rejection, `NOT NULL` rejection,
`WHERE` filtering (including `AND`), `ORDER BY DESC`, `UPDATE`,
`DELETE`, a `JOIN` combined with `WHERE`, and data + schema surviving a
full restart.

## Roadmap

- [x] **Storage Engine** — page format, disk I/O, buffer pool, heap files
- [x] **Catalog / Metadata Manager** — table schemas, column types, `CREATE TABLE` / `DROP TABLE`
- [x] **SQL Parser** — tokenizer + recursive-descent parser → AST for
      `SELECT` / `INSERT` / `UPDATE` / `DELETE` / `CREATE TABLE` / `DROP TABLE`,
      with `WHERE`, `JOIN`, `ORDER BY`
- [x] **Query Executor** — table scans, filters, projections, nested-loop joins
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
- **Metadata stored using the engine's own storage, not a side format** —
  the catalog doesn't invent a second persistence mechanism; it reuses
  `HeapFile` to store schema rows. One storage engine to test and trust,
  not two.
- **Hand-written parser over a parser generator** — recursive descent
  means the grammar and the code implementing it live side by side; it's
  slower to write than pointing ANTLR at a `.g4` file, but there's nothing
  generated or opaque to explain in an interview.
- **Materialize before mutate** — `UPDATE`/`DELETE` first collect every
  matching row's RID into a list, *then* mutate. Deleting/updating while
  a `HeapFile` scan iterator is still walking the same pages is a classic
  source of skipped or double-processed rows; separating "find" from
  "act" avoids it entirely.
