# Cutover plan: from the nightly file to events, one partner at a time

Lesson step 6. The plan has a rollback and a retirement condition that is a number.

## Before anything changes

1. The flow, the order service and the legacy loader have run side by side for at least one week of real files; every morning's `reconcile` is recorded in `reconciliation_run`.
2. The partner has agreed a date and a rollback signal (a phone number, not a ticket).
3. On-call knows the four queue names, the `status` command and this page.

## The steps

| # | Step | How you know it worked | If not |
| --- | --- | --- | --- |
| 1 | Ask the partner to keep sending the file **and** start sending each order as a message to the flow's input (an HTTP or message endpoint feeding the same `lines`/`valid` channels). | `status` shows both the file's orders and the live ones; the flow's duplicate counter rises (the same orders arrive twice). | Stop the partner's live feed; nothing else changed. |
| 2 | Run one more clean week: `reconcile` still reports zero differences, and the delay from the partner's send to the order system (the `received_at` column) is seconds. | 7 more rows in `reconciliation_run`, all clean. | Fix the cause (the differing ids are listed); the count restarts. |
| 3 | Check the condition: `reconcile` prints `legacy path may be retired` (14 clean nights in a row, `ORDERFLOW_CLEAN_NIGHTS_TO_RETIRE`). | The exit code is 0 and the line is printed. | Wait. |
| 4 | Stop the partner's nightly file. Keep the SFTP folder and the legacy loader **enabled and idle** for two more weeks. | No file arrives; orders keep arriving by message. | Step Rollback. |
| 5 | Retire the legacy loader and remove the folder. | Nothing reads `orders_batch` any more; archive the table. | Restore from the archive; the loader is in git. |

## Rollback

At any step before 5: tell the partner to go back to the nightly file (they never stopped sending it until step 4), and re-enable the legacy loader if it was stopped. The loader's input folder is still watched, `orders_batch` is still complete up to the cutover, and the reconciliation shows exactly which orders arrived only by message in the meantime, so they can be loaded from the new table.

## What to watch during the switch

| Signal | Where | Worry when |
| --- | --- | --- |
| Dead letters | queue `dead-letter` (`status`) | it grows faster than the partner's usual bad lines (about three per file) |
| Duplicates | the flow's `duplicate(s) dropped` line | it is zero while the partner sends both feeds (the receiver is not working) |
| Delay | `received_at` against the partner's timestamp | more than a minute |
| Rejected orders | queue `rejected` | not empty: the order service refused a valid-looking order |
| Differences | `reconcile` | not clean: stop, and list the ids |
