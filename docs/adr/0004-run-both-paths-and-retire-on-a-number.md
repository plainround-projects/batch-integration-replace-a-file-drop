# 4. Run the old and the new path side by side, and retire the old one on a number

## Context

Replacing a file transfer in one step means betting that the new path is complete the first time. A bad guess is found by the partner's finance team.

## Decision

The legacy loader keeps running into its own table; the flow runs beside it; every morning `reconcile` compares the two by order id, amount, status, customer and type with one `FULL OUTER JOIN` (`sql/reconcile.sql`), prints the four counts and the ids behind any difference, and appends the result to `reconciliation_run`. `RetirementPolicy` says the legacy path may be retired after 14 clean nights in a row; one dirty night resets the count. The cutover plan in `docs/CUTOVER.md` is built on that number and has a rollback.

## Alternatives

- Retire after one clean night: a week of files can hide the first-of-the-month behavior.
- Compare only counts: two compensating errors (one lost, one invented) cancel.

## Consequences

- The decision to switch is an exit code and a table, not an opinion.
- The comparison is only as good as the two paths' independence (ADR 2).
