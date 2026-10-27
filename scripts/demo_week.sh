#!/bin/sh
# Lesson steps 3 and 5: seven nightly files go through BOTH paths. The new path delivers within seconds (the flow and
# the order service must be running: `make apps`); the legacy batch loads them "the next morning". The reconciliation then
# shows no difference; a planted difference (one amount changed in transit) is caught and listed.
set -eu
JAR="${JAR:-target/replace-a-file-drop-0.1.0.jar}"
PSQL="docker compose exec -T postgres psql -U ${POSTGRES_USER:-orderflow} -d ${POSTGRES_DB:-orderflow} -v ON_ERROR_STOP=1 -q"
EVENTS="$PSQL -tAc"

$PSQL < sql/reset.sql
docker compose exec -T sftp sh -c 'rm -f /home/partner/upload/*.csv'
for queue in retail wholesale dead-letter rejected; do docker compose exec -T rabbit rabbitmqctl purge_queue "$queue" > /dev/null; done
night=1
while [ "$night" -le 7 ]; do
  java -jar "$JAR" --command=partner --night="$night" --seed="$night"
  expected=$((night * 9997))
  waited=0
  while [ "$($EVENTS 'SELECT count(*) FROM orders_events')" -lt "$expected" ] && [ "$waited" -lt 120 ]; do sleep 2; waited=$((waited + 2)); done
  echo "night $night: new path holds $($EVENTS 'SELECT count(*) FROM orders_events') orders after ${waited}s of waiting"
  night=$((night + 1))
done

echo "== the next morning: the legacy batch loads the week's files"
java -jar "$JAR" --command=legacy-load
echo "== reconciliation of a clean week"
java -jar "$JAR" --command=reconcile
echo "== one order changed in transit (a transformer bug, planted)"
$PSQL -c "UPDATE orders_events SET amount = amount + 1 WHERE order_id = 'N03O000042'"
java -jar "$JAR" --command=reconcile || echo "(exit code $?, the planted difference was caught)"
$PSQL -c "UPDATE orders_events SET amount = amount - 1 WHERE order_id = 'N03O000042'"
