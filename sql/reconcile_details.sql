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
