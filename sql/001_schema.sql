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

-- Which partner files the legacy loader has already loaded, so it does not load one twice.
CREATE TABLE IF NOT EXISTS legacy_loaded_file (
    file_name TEXT        PRIMARY KEY,
    orders    INTEGER     NOT NULL,
    loaded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
