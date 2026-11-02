# PostgreSQL and the SQL files

## What it is

PostgreSQL ("Postgres") is a free, open-source relational database **server**: a long-running program that stores tables on disk, listens on a network port (5432 by default) and answers SQL sent by clients. It provides transactions with strong guarantees (ACID: a transaction is *atomic*, leaves the data *consistent*, is *isolated* from others and is *durable* once committed), many data types, and powerful SQL. **`psql`** is its command-line client: you give it a user, a database and SQL (from the keyboard, from a file with `<`, or from `-c "..."`).

Words you need: a **database** is a named collection of tables inside the server; a **role** (user) owns objects and logs in with a password; a **schema** groups tables inside a database (the default is `public`); a **sequence** is a counter object that hands out the next number; the **WAL** (write-ahead log) is the journal the server writes before changing data so it can recover after a crash; `fsync` is the call that forces that journal onto the disk.

SQL itself, the language of every `.sql` file in this repository, is explained next.

SQL (Structured Query Language) is the language used to talk to relational databases. A **relational database** stores data in **tables**; a table is a grid with named **columns** and one **row** per thing. SQL has four groups of statements:

- **DDL** (data definition) creates and changes the structure: `CREATE TABLE`, `CREATE INDEX`, `DROP INDEX`, `ALTER TABLE`.
- **DML** (data manipulation) changes the data: `INSERT`, `UPDATE`, `DELETE`, `TRUNCATE`.
- **Queries** read data: `SELECT ... FROM ... WHERE ... GROUP BY ... ORDER BY`.
- **DCL** (data control) manages permissions: `GRANT`, `REVOKE`.

Key ideas: a **primary key** is the column (or columns) whose value identifies a row and must be unique; a **foreign key** is a column that must match the primary key of another table; a **constraint** (`NOT NULL`, `CHECK (...)`, `UNIQUE`) is a rule the database enforces on every write; an **index** is an extra structure that lets the database find rows quickly without reading the whole table; `NULL` means "no value" and is neither equal nor unequal to anything, which is why SQL has `IS NULL` and `COALESCE(x, default)`. Comments start with `--` and run to the end of the line.

## Why this repository uses it

Both paths of the lesson end in a PostgreSQL table: the legacy batch loader writes `orders_batch`, the order service writes `orders_events`. The reconciliation, the lesson's proof that the new path can replace the old one, is one SQL query over the two tables. The same database also holds Spring Integration's **metadata store**: the memory that makes the flow remember, across restarts, which files it fetched and which orders it published.

## Where it appears

| Path | What it does |
| --- | --- |
| `sql/001_schema.sql` | The metadata store, the two order tables and the loaded-file record. |
| `sql/migrations/001_reconciliation_history.sql` | One row per nightly reconciliation. |
| `sql/reconcile.sql` | The one-pass comparison of the two paths. |
| `sql/reconcile_details.sql` | The orders behind each difference. |
| `sql/002_queries.sql` | The lesson's checks (`make queries`). |
| `sql/reset.sql` | Forgets everything (`make reset`, the tests). |
| `docker-compose.yml` | The server and its settings. |

## How it works underneath

The server starts one **backend process** per connection. A client (the Java program, `psql`) connects over TCP, authenticates, and sends statements. For a write, the server records the change in the WAL, changes the pages in its memory cache (`shared_buffers`), and flushes them to disk later in the background (a **checkpoint**). With `fsync=on` (the default) a commit waits until its WAL record is safely on disk; with `fsync=off` it does not wait, which is fast but can lose or corrupt data if the machine crashes. The benchmark database in this repository turns it off on purpose and says so.

`pg_isready` asks the server whether it accepts connections. `pg_stat_activity` is a built-in view listing every connection and its state (`active`, `idle`, `idle in transaction`); `pg_locks` lists locks, with `granted = false` for those waiting. A connection that is `idle in transaction` holds its transaction open without running anything.

A SQL statement describes **what** you want, not **how** to get it. The database's **planner** chooses the method: it can scan the whole table (a *sequential scan*) or use an index. A `JOIN` combines rows of two tables that match a condition; `LEFT JOIN` keeps the rows of the left table even when there is no match. `GROUP BY` collapses rows that share values and lets you compute totals with functions such as `SUM`, `COUNT`, `MAX`. An aggregate over no rows gives `NULL` (and `COUNT` gives 0).

A **transaction** groups statements: `BEGIN` (or the first statement in a session with autocommit off) starts, `COMMIT` makes the changes permanent and visible to others, `ROLLBACK` throws them away. By default `psql` runs each statement in its own transaction (autocommit).

## Walkthrough of this repository's use

### `sql/001_schema.sql`: the tables

```sql
-- DDL: the two paths' tables, the idempotent receiver's memory, and the legacy loader's bookkeeping.
--
--   orders_batch   written ONLY by the legacy nightly batch loader
--   orders_events  written ONLY by the order service, from the broker
-- Reconciliation joins them. If both paths wrote one table, comparing it with itself would report zero differences for ever.

-- Spring Integration's JDBC metadata store, as shipped in spring-integration-jdbc 6.5 (schema-postgresql.sql), made
-- repeatable. It holds the order ids the idempotent receiver has seen (region "orders") and the files the SFTP inbound
-- adapter has fetched (region "files"). It lives in the database, so a restart does not forget them.
CREATE TABLE IF NOT EXISTS INT_METADATA_STORE (
    METADATA_KEY   VARCHAR(255) NOT NULL,
    METADATA_VALUE VARCHAR(4000),
    REGION         VARCHAR(100) NOT NULL,
    CONSTRAINT INT_METADATA_STORE_PK PRIMARY KEY (METADATA_KEY, REGION)
);
```

Lines 1 to 5 are comments (SQL comments start with two dashes). The key design rule is on lines 3 to 5: each path writes only its own table, because "if both paths wrote one table, comparing it with itself would report zero differences for ever". `INT_METADATA_STORE` is the table Spring Integration's `JdbcMetadataStore` uses (the Spring Integration note explains what the store does); the script is the library's own PostgreSQL schema "made repeatable" with `IF NOT EXISTS`, so `make db` can be run again. It has a key (`METADATA_KEY`, up to 255 characters), a value (up to 4,000), and a `REGION` that separates independent memories: `orders` (the order ids seen), `dead-letters` (refused lines seen) and `files` (SFTP files fetched). `CONSTRAINT INT_METADATA_STORE_PK PRIMARY KEY (METADATA_KEY, REGION)` makes the pair unique; a **primary key** is a column (or set of columns) whose value identifies one row, and the database refuses a second row with the same value.

```sql
CREATE TABLE IF NOT EXISTS orders_batch (
    order_id  TEXT          PRIMARY KEY,
    customer  TEXT          NOT NULL,
    type      TEXT          NOT NULL CHECK (type IN ('retail', 'wholesale')),
    amount    NUMERIC(12,2) NOT NULL CHECK (amount > 0),
    status    TEXT          NOT NULL DEFAULT 'RECEIVED',
    loaded_at TIMESTAMPTZ   NOT NULL DEFAULT now()
);
COMMENT ON TABLE orders_batch IS 'The legacy path: loaded by the nightly batch job from the partner file, the next morning.';

CREATE TABLE IF NOT EXISTS orders_events (
    order_id    TEXT          PRIMARY KEY,
    customer    TEXT          NOT NULL,
    type        TEXT          NOT NULL CHECK (type IN ('retail', 'wholesale')),
    amount      NUMERIC(12,2) NOT NULL CHECK (amount > 0),
    status      TEXT          NOT NULL DEFAULT 'RECEIVED',
    received_at TIMESTAMPTZ   NOT NULL DEFAULT now()
);
COMMENT ON TABLE orders_events IS 'The new path: written by the order service from the broker, within seconds. The primary key is the idempotent consumer.';
```

`orders_batch` and `orders_events` have identical columns. `order_id TEXT PRIMARY KEY` is the business key from the partner. `customer TEXT NOT NULL`: a value is required. `type TEXT NOT NULL CHECK (type IN ('retail', 'wholesale'))`: the database itself refuses any other type. `amount NUMERIC(12,2) NOT NULL CHECK (amount > 0)`: an exact decimal with up to 12 digits and 2 after the point, which must be positive. `status TEXT NOT NULL DEFAULT 'RECEIVED'`: when an insert gives no status the database fills in `RECEIVED`. `loaded_at TIMESTAMPTZ NOT NULL DEFAULT now()` (`received_at` in the other table) is a timestamp with time zone filled in with the current time. `COMMENT ON TABLE` stores a description inside the database.

```sql
-- Which partner files the legacy loader has already loaded, so it does not load one twice.
CREATE TABLE IF NOT EXISTS legacy_loaded_file (
    file_name TEXT        PRIMARY KEY,
    orders    INTEGER     NOT NULL,
    loaded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

`legacy_loaded_file` records which partner files the legacy loader has already loaded (file name as the key, the number of orders, the time), so it does not load one twice.

### `sql/migrations/001_reconciliation_history.sql`

```sql
-- Migration 001 (expand only): one row per reconciliation run, kept so the cutover decision can be a number
-- ("14 nights in a row with no difference") instead of a feeling.

CREATE TABLE IF NOT EXISTS reconciliation_run (
    run_id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    only_in_batch  BIGINT      NOT NULL,
    only_in_events BIGINT      NOT NULL,
    different      BIGINT      NOT NULL,
    same           BIGINT      NOT NULL
);
COMMENT ON TABLE reconciliation_run IS 'Each night''s comparison of orders_batch and orders_events.';
```

A **migration** is a change to an existing database's structure, kept as a numbered file; this one only adds ("expand only"). The comment says why: the cutover decision should be a number ("14 nights in a row with no difference"), so each comparison is kept. `run_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY` is a 64-bit number the database assigns, one more for every row (`ALWAYS` means an insert may not set it). `run_at TIMESTAMPTZ NOT NULL DEFAULT now()` records when. The four `BIGINT NOT NULL` columns hold the four counts. On the last line the text `'Each night''s comparison...'` writes an apostrophe inside a quoted string by doubling it.

### `sql/reconcile.sql`: the comparison in one pass

```sql
-- The reconciliation (lesson step 5): compare the two paths by order id, amount and status in ONE pass, in both
-- directions. FULL OUTER JOIN keeps rows present on only one side; the FILTER clauses count each case.
SELECT
  count(*) FILTER (WHERE e.order_id IS NULL)                          AS only_in_batch,
  count(*) FILTER (WHERE b.order_id IS NULL)                          AS only_in_events,
  count(*) FILTER (WHERE b.order_id IS NOT NULL AND e.order_id IS NOT NULL
                     AND (b.amount IS DISTINCT FROM e.amount
                          OR b.status IS DISTINCT FROM e.status
                          OR b.customer IS DISTINCT FROM e.customer
                          OR b.type IS DISTINCT FROM e.type))         AS different,
  count(*) FILTER (WHERE b.amount = e.amount AND b.status = e.status
                     AND b.customer = e.customer AND b.type = e.type) AS same
FROM orders_batch b
FULL OUTER JOIN orders_events e ON e.order_id = b.order_id;
```

`FROM orders_batch b FULL OUTER JOIN orders_events e ON e.order_id = b.order_id` joins the two tables by order id and keeps rows from **both** sides even when the other side has no match (`b` and `e` are short names, **aliases**, for the tables). For an order only in the batch table, every `e` column is `NULL`; for an order only in the events table, every `b` column is `NULL`. Then four counts are made in a single scan with `count(*) FILTER (WHERE condition)`, which counts only the rows that satisfy the condition. `only_in_batch` counts rows where `e.order_id IS NULL`; `only_in_events` where `b.order_id IS NULL`. `different` counts rows present on both sides where any of amount, status, customer or type is different; `IS DISTINCT FROM` is a comparison that treats `NULL` as an ordinary value (plain `<>` gives "unknown" when either side is `NULL`). `same` counts rows where all four fields are equal. Comparing amount, status, customer and type, not just ids, is the point: two paths can agree on the ids and still disagree on an amount, which is what the lesson's planted difference shows.

### `sql/reconcile_details.sql`: which orders

```sql
-- The next step after the counts: the ids behind every difference, with what each side holds.
SELECT coalesce(b.order_id, e.order_id) AS order_id,
       CASE WHEN e.order_id IS NULL THEN 'only in batch'
            WHEN b.order_id IS NULL THEN 'only in events'
            ELSE 'different' END AS kind,
       b.amount AS batch_amount, e.amount AS events_amount,
       b.status AS batch_status, e.status AS events_status
FROM orders_batch b
FULL OUTER JOIN orders_events e ON e.order_id = b.order_id
WHERE b.order_id IS NULL OR e.order_id IS NULL
   OR b.amount IS DISTINCT FROM e.amount OR b.status IS DISTINCT FROM e.status
   OR b.customer IS DISTINCT FROM e.customer OR b.type IS DISTINCT FROM e.type
ORDER BY 1
LIMIT ?;
```

The same join, but returning one row per difference. `coalesce(b.order_id, e.order_id)` returns the first value that is not `NULL`, giving the order id whichever side has it. The `CASE WHEN ... THEN ... ELSE ... END` expression labels each row `only in batch`, `only in events` or `different`. The `WHERE` keeps only rows that are on one side only or differ. `ORDER BY 1` sorts by the first column; `LIMIT ?` takes the limit as a parameter from the Java code.

### `sql/reset.sql`

```sql
-- Outside the numbered run order: forget both paths' orders and everything the receiver remembers (used by
-- `make reset` and the tests). Messages already on the broker are not touched; `make purge` empties the queues.
TRUNCATE orders_batch, orders_events, legacy_loaded_file;
DELETE FROM INT_METADATA_STORE;
DO $$
BEGIN
    IF to_regclass('reconciliation_run') IS NOT NULL THEN
        TRUNCATE reconciliation_run;
    END IF;
END $$;
```

`TRUNCATE` empties tables instantly without scanning rows; line 3 forgets both paths' orders and the loaded files. `DELETE FROM INT_METADATA_STORE` forgets everything the flow remembers. The `DO $$ ... $$;` block runs a short program in PL/pgSQL (PostgreSQL's procedural language; `$$` quotes the body): `to_regclass('reconciliation_run')` returns `NULL` if the table does not exist, so the block empties it only when it exists (the migration may not have run).

### `sql/002_queries.sql`: the lesson's checks

```sql
-- The queries the lesson runs, each with a one-line comment on what it shows.

-- Orders on each path. After one file: 9,997 on both.
SELECT 'orders_batch' AS path, count(*) FROM orders_batch UNION ALL SELECT 'orders_events', count(*) FROM orders_events;

-- Orders per type on the new path (the broker queues retail and wholesale should add up to this).
SELECT type, count(*) FROM orders_events GROUP BY type ORDER BY type;

-- The idempotent receiver's memory: order ids seen, and files fetched.
SELECT region, count(*) FROM int_metadata_store GROUP BY region ORDER BY region;

-- The legacy loader's files.
SELECT file_name, orders, loaded_at FROM legacy_loaded_file ORDER BY loaded_at;

-- The reconciliation counts are sql/reconcile.sql (run by `make reconcile`); the ids behind them, sql/reconcile_details.sql.

-- The history of nightly comparisons: the cutover condition reads the latest rows.
SELECT run_id, run_at, only_in_batch, only_in_events, different, same FROM reconciliation_run ORDER BY run_id DESC LIMIT 14;
```

Each query has a comment. The first counts both paths with `UNION ALL`, which stacks two result sets one after the other (`'orders_batch' AS path` names the column). The second counts orders per type with `GROUP BY type`. The third counts rows of the metadata store per region, which shows how many order ids and files the flow remembers. The fourth lists the loaded files in time order. The last shows the 14 newest reconciliations.

## Try it yourself

1. Open a SQL prompt: `docker compose exec postgres psql -U orderflow -d orderflow`. Type `\dt` to list tables, `\d orders_events` to describe one, `\q` to leave.
2. Count both paths after `make apps partner` and `make legacy-load`: `make queries`; the first result shows 9,997 on both.
3. Run the reconciliation SQL by hand: `docker compose exec -T postgres psql -U orderflow -d orderflow < sql/reconcile.sql` prints four numbers.
4. Plant a difference: `update orders_events set amount = amount + 1 where order_id = 'N01O000042';` then rerun step 3: `different` is 1 and `same` is 9,996. Undo it with `- 1`.
5. Break a constraint: `insert into orders_events values ('Z','C','barter',5);` fails with a message that the check constraint is violated.

## Mistakes people make

- **`fsync=off` on real data.** Only for disposable databases.
- **Too few connections allowed.** `max_connections` must exceed what the application's pool opens (here up to 80 for the scaling probe; the compose file sets 200).
- **Forgetting that `TRUNCATE` is immediate and cannot be filtered.** It empties the whole table.
- **Running the server without enough shared memory in a container.** Parallel queries fail with "No space left on device" on `/dev/shm`; the compose file sets `shm_size`.
- **Using the `postgres` superuser for the application.** The application here uses an ordinary role created for the project.

- **`= NULL` instead of `IS NULL`.** The first is never true.
- **Forgetting the index** on the column you search by. A query that is fine with a thousand rows becomes minutes with millions (the lesson's step 1 to 2).
- **`UPDATE` or `DELETE` without `WHERE`.** It changes every row.
- **Adding instead of setting** in an upsert (`points = points + 1`): a retry doubles the effect.
- **Building SQL from strings that include user input.** Use parameters.
- **Assuming row order.** Without `ORDER BY` the order is not defined.

## Check yourself

1. Why do the two paths write different tables? *So the reconciliation compares two independent results; one shared table would always agree with itself.*
2. What does `FULL OUTER JOIN` keep that `JOIN` drops? *Rows that have no partner on the other side.*
3. What does `count(*) FILTER (WHERE ...)` do? *Counts only the rows that satisfy the condition, so several counts can be made in one scan.*
4. Why `IS DISTINCT FROM`? *It compares `NULL` as a value; `<>` gives unknown for `NULL`.*
5. What is the `REGION` column for? *It keeps independent memories (orders, dead letters, files) in one metadata table.*

## Further reading

- PostgreSQL 16 documentation: https://www.postgresql.org/docs/16/
- `psql` reference: https://www.postgresql.org/docs/16/app-psql.html
- Monitoring views (`pg_stat_activity`): https://www.postgresql.org/docs/16/monitoring-stats.html
- Server configuration (`max_connections`, `fsync`, `shared_buffers`): https://www.postgresql.org/docs/16/runtime-config.html
- Docker image `postgres:16`: https://hub.docker.com/_/postgres

- PostgreSQL 16 SQL language reference: https://www.postgresql.org/docs/16/sql.html
- SQL commands index: https://www.postgresql.org/docs/16/sql-commands.html
- Indexes: https://www.postgresql.org/docs/16/indexes.html
