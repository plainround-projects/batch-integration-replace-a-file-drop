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
