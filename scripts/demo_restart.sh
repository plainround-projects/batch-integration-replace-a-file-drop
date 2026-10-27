#!/bin/sh
# Lesson step 4: restart the broker, and then the order service, in the middle of a file. Nothing may be lost or doubled.
# Needs: make up db apps. A 10,000-order file takes the flow several seconds to publish, long enough to interrupt.
set -eu
JAR="${JAR:-target/replace-a-file-drop-0.1.0.jar}"
PSQL="docker compose exec -T postgres psql -U ${POSTGRES_USER:-orderflow} -d ${POSTGRES_DB:-orderflow} -v ON_ERROR_STOP=1 -q"
COUNT="$PSQL -tAc"

wait_for() { # target count, seconds
  waited=0
  while [ "$($COUNT 'SELECT count(*) FROM orders_events')" -lt "$1" ] && [ "$waited" -lt "$2" ]; do sleep 2; waited=$((waited + 2)); done
}

$PSQL < sql/reset.sql
for queue in retail wholesale dead-letter rejected; do docker compose exec -T rabbit rabbitmqctl purge_queue "$queue" > /dev/null; done

echo "== night 11: restart the broker while the flow is publishing"
java -jar "$JAR" --command=partner --night=11 --seed=11
sleep 4
docker restart "$(docker compose ps -q rabbit)" > /dev/null
wait_for 9997 240
echo "orders on the new path: $($COUNT 'SELECT count(*) FROM orders_events') (expected 9997), distinct: $($COUNT 'SELECT count(DISTINCT order_id) FROM orders_events')"

echo "== night 12: restart the order service while it is consuming"
java -jar "$JAR" --command=partner --night=12 --seed=12
sleep 4
docker restart "$(docker compose ps -q order-service)" > /dev/null
wait_for 19994 240
echo "orders on the new path: $($COUNT 'SELECT count(*) FROM orders_events') (expected 19994), distinct: $($COUNT 'SELECT count(DISTINCT order_id) FROM orders_events')"
echo "== status"
java -jar "$JAR" --command=status
