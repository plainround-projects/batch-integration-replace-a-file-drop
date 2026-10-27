# 2. Idempotence at the receiver and at the consumer, and one table per path

## Context

Duplicates arise at every hop: the partner resends files, a flow restarts and republishes, a broker redelivers at least once, a consumer retries. No single place can remove them all.

## Decision

Two places remove them. The flow's receiver drops an order id it has already published (efficiency, and a clean broker). The order service's `INSERT ... ON CONFLICT (order_id) DO NOTHING` makes a second delivery a no-op (correctness). The new path writes `orders_events` and the legacy loader writes `orders_batch`; neither touches the other's table.

## Alternatives

- Only the consumer: correct, and every resend costs the broker a full file.
- Both paths writing one table: the reconciliation would compare the table with itself and report zero differences for ever (the lesson's named mistake).

## Consequences

- Delivery at the broker can duplicate; the table cannot.
- The reconciliation is a real comparison of two independent writers.
