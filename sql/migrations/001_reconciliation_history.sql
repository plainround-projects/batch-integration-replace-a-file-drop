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
