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
