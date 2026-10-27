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
