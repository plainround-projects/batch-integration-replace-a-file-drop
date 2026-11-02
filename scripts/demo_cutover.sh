#!/bin/sh
# Lesson step 6: the retirement condition is a number. Record 14 nights of clean reconciliations and ask the program;
# a dirty night in the middle resets the count.
set -eu
JAR="${JAR:-target/replace-a-file-drop-0.1.0.jar}"
PSQL="docker compose exec -T postgres psql -U ${POSTGRES_USER:-orderflow} -d ${POSTGRES_DB:-orderflow} -v ON_ERROR_STOP=1 -q"

# Both order tables empty: the nightly comparison is clean, so the count of clean nights is the only thing under test.
$PSQL -c "TRUNCATE orders_batch, orders_events, legacy_loaded_file, reconciliation_run"
echo "== 13 clean nights: not enough"
$PSQL -c "INSERT INTO reconciliation_run (only_in_batch, only_in_events, different, same) SELECT 0, 0, 0, 9997 FROM generate_series(1, 12)"
java -jar "$JAR" --command=reconcile | tail -1
echo "== the 14th clean night: the legacy path may be retired"
java -jar "$JAR" --command=reconcile | tail -1
echo "== a night with one difference resets the count"
$PSQL -c "INSERT INTO reconciliation_run (only_in_batch, only_in_events, different, same) VALUES (0, 0, 1, 9996)"
java -jar "$JAR" --command=reconcile | tail -1
