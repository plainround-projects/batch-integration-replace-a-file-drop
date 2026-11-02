# Bash and shell scripts

## What it is

A **terminal** is a window where you type commands as text and read the answers as text. The program inside it that reads what you type and runs it is called a **shell**. The shell used on most Macs and Linux machines is **Bash**; a smaller, older language that Bash also understands is the **POSIX shell** (`sh`). A **shell script** is a text file of commands that the shell runs top to bottom, so you can do in one command what would otherwise be twenty typed lines.

A command is a program name followed by **arguments**: in `ls -l src`, `ls` is the program, `-l` is an **option** (a flag that changes behavior) and `src` is an argument. Every program, when it ends, returns a number called its **exit code**: `0` means success and any other number means failure. The shell remembers the last exit code in the variable `$?`.

## Why this repository uses it

The lesson's steps 3 to 6 need sequences that a single command cannot express and a unit test should not do: drop seven nightly files and wait for each to arrive, restart the broker in the middle of a file, restart the order service, plant a difference and catch it, record fourteen clean nights and ask the program about them. Each sequence is a short script, called by a `make` target so a reader types one word.

## Where it appears

| Path | What it does |
| --- | --- |
| `scripts/demo_week.sh` | Seven nightly files through both paths, then the reconciliation and the planted difference (`make demo-week`). |
| `scripts/demo_restart.sh` | Restarts the broker and then the order service in the middle of a file (`make demo-restart`). |
| `scripts/demo_cutover.sh` | The retirement condition as a number (`make demo-cutover`). |
| `mvnw` | The Maven Wrapper, itself a long shell script (see the Maven Wrapper note). |
| `Makefile` | Its recipes are shell commands. |
| `.github/workflows/ci.yml` | `run:` steps are shell commands. |

## How it works underneath

The first line of a script, `#!/bin/sh`, is the **shebang**: it tells the operating system which program should run the file. When you start `sh scripts/benchmark.sh` the shell reads the file one line at a time and executes each command in order.

The pieces this repository's scripts use:

- **Variables.** `name=value` stores text (no spaces around `=`); `$name` or `${name}` reads it. `${name:-default}` means "the value of `name`, or `default` if it is unset or empty". Variables the shell passes on to programs it starts are **environment variables**; `export name` or writing `NAME=value command` makes a variable visible to that command.
- **Quotes.** Double quotes `"..."` keep the text together as one word and still expand `$variables`; single quotes `'...'` keep everything literally. Without quotes the shell splits on spaces, which breaks file names that contain spaces. Always quote variables.
- **Command substitution.** `$(command)` runs the command and puts its output in place.
- **Pipes.** `a | b` connects the standard output of `a` to the standard input of `b`, so data flows from one program into the next. `tr`, `sed`, `grep`, `head`, `cut` are small programs built for pipelines.
- **Redirection.** `> file` writes standard output to a file (replacing it), `>> file` appends, `< file` reads standard input from a file, `2> file` redirects standard error, `2>&1` sends standard error to wherever standard output goes, `> /dev/null` throws output away.
- **Lists.** `a && b` runs `b` only if `a` succeeded; `a || b` runs `b` only if `a` failed; `a; b` runs both; `a &` starts `a` in the background and `wait` waits for background jobs.
- **Conditions and loops.** `if ...; then ...; fi`, `case x in pattern) ...;; esac`, `for x in list; do ...; done`, `while ...; do ...; done`, `until ...; do ...; done`.
- **Functions.** `name() { ...; }` defines a reusable block; `$1`, `$2` are its arguments.
- **`set -eu`.** `set -e` stops the script at the first command that fails; `set -u` stops it when a variable that was never set is used. Together they turn silent mistakes into loud ones.
- **`trap`.** `trap 'command' EXIT` runs `command` when the script ends, whatever the reason; used to delete temporary files.

## Walkthrough of this repository's use

### `scripts/demo_cutover.sh`: the shortest script

```bash
#!/bin/sh
# Lesson step 6: the retirement condition is a number. Record 14 nights of clean reconciliations and ask the program;
# a dirty night in the middle resets the count.
set -eu
JAR="${JAR:-target/replace-a-file-drop-0.1.0.jar}"
PSQL="docker compose exec -T postgres psql -U ${POSTGRES_USER:-orderflow} -d ${POSTGRES_DB:-orderflow} -v ON_ERROR_STOP=1 -q"

$PSQL -c "TRUNCATE reconciliation_run"
echo "== 13 clean nights: not enough"
$PSQL -c "INSERT INTO reconciliation_run (only_in_batch, only_in_events, different, same) SELECT 0, 0, 0, 9997 FROM generate_series(1, 12)"
java -jar "$JAR" --command=reconcile | tail -1
echo "== the 14th clean night: the legacy path may be retired"
java -jar "$JAR" --command=reconcile | tail -1
echo "== a night with one difference resets the count"
$PSQL -c "INSERT INTO reconciliation_run (only_in_batch, only_in_events, different, same) VALUES (0, 0, 1, 9996)"
java -jar "$JAR" --command=reconcile | tail -1
```

Line 1 is the shebang; `/bin/sh` is the POSIX shell. Lines 2 and 3 are comments. Line 4, `set -eu`, stops on the first failing command and on any unset variable. Line 5, `JAR="${JAR:-target/replace-a-file-drop-0.1.0.jar}"`, sets `JAR` to the environment's value or the default path. Line 6 builds the `psql` command in a variable `PSQL` (the options are explained in the Make note), using the database user and name from the environment or defaults.

Line 8 empties the history table (`-c` runs one SQL command). Line 10 inserts twelve clean nights at once: `INSERT INTO reconciliation_run (...) SELECT 0, 0, 0, 9997 FROM generate_series(1, 12)` selects the constants once for each number from 1 to 12 (`generate_series` produces those numbers as rows). Line 11 runs the reconciliation, which records the 13th clean night; `| tail -1` keeps only the last line of its output, the verdict. With 13 nights and a rule of 14, that line says the legacy path stays. Line 13 runs it again: the 14th clean night, so the verdict says the legacy path may be retired. Line 15 inserts a night with one difference; line 16 shows the verdict again: it stays, because the newest 14 are no longer all clean.

### `scripts/demo_week.sh`: seven nights

```bash
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
```

Lines 6 to 8 set the jar, the `psql` command and a second command `EVENTS` that adds `-tAc` (`-t` print only rows, `-A` unaligned, `-c` run this one command), so a query prints a bare value. Line 10 resets the database by redirecting `reset.sql` into `psql`. Line 11 removes old CSV files from the SFTP container (`docker compose exec -T sftp sh -c '...'`). Line 12 purges the four queues in a `for` loop, throwing away the output with `> /dev/null`.

```bash
night=1
while [ "$night" -le 7 ]; do
  java -jar "$JAR" --command=partner --night="$night" --seed="$night"
  expected=$((night * 9997))
  waited=0
  while [ "$($EVENTS 'SELECT count(*) FROM orders_events')" -lt "$expected" ] && [ "$waited" -lt 120 ]; do sleep 2; waited=$((waited + 2)); done
  echo "night $night: new path holds $($EVENTS 'SELECT count(*) FROM orders_events') orders after ${waited}s of waiting"
  night=$((night + 1))
done
```

The loop runs once per night. `night=1; while [ "$night" -le 7 ]; do ... done` repeats while the counter is at most 7 (`-le`). Each pass uploads the night's file (`--night="$night" --seed="$night"`), computes how many orders the new path should hold (`expected=$((night * 9997))`: `$(( ))` is arithmetic), and waits: the inner `while` loop continues while the count in `orders_events` is below `expected` and `waited` is below 120 seconds, sleeping 2 seconds per pass. `$($EVENTS 'SELECT count(*) FROM orders_events')` runs the query and puts its output into the test. Then it prints how many orders the new path holds and how long it waited, and moves to the next night.

```bash
echo "== the next morning: the legacy batch loads the week's files"
java -jar "$JAR" --command=legacy-load
echo "== reconciliation of a clean week"
java -jar "$JAR" --command=reconcile
echo "== one order changed in transit (a transformer bug, planted)"
$PSQL -c "UPDATE orders_events SET amount = amount + 1 WHERE order_id = 'N03O000042'"
java -jar "$JAR" --command=reconcile || echo "(exit code $?, the planted difference was caught)"
$PSQL -c "UPDATE orders_events SET amount = amount - 1 WHERE order_id = 'N03O000042'"
```

"The next morning": the legacy loader loads the seven files. The reconciliation of a clean week prints four counts with no difference. Then a difference is planted: `UPDATE orders_events SET amount = amount + 1 WHERE order_id = 'N03O000042'` (a transformer bug in transit), and `java -jar "$JAR" --command=reconcile || echo "(exit code $?, the planted difference was caught)"` runs the reconciliation, which exits with 1; the `||` branch runs only when the command fails and prints the exit code (`$?`), and it also stops `set -e` from ending the script. The last line undoes the change.

### `scripts/demo_restart.sh`: interrupting on purpose

```bash
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
docker compose exec -T sftp sh -c 'rm -f /home/partner/upload/*.csv'
for queue in retail wholesale dead-letter rejected; do docker compose exec -T rabbit rabbitmqctl purge_queue "$queue" > /dev/null; done
```

`wait_for() { ... }` defines a **function** (a named group of commands): its arguments are `$1`, the target count, and `$2`, the maximum seconds to wait. The loop inside is the same wait as before. Lines 14 to 16 reset the database, clear the folder and purge the queues.

```bash
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
```

Night 11: upload the file, `sleep 4` so the flow has begun publishing, then `docker restart "$(docker compose ps -q rabbit)"`: `docker compose ps -q rabbit` prints the id of the broker's container, and `docker restart` stops and starts it. The flow's publishes in flight fail and are retried after the broker returns; `wait_for 9997 240` waits for all orders to reach the table, and the script prints the count and the number of distinct ids (expected 9,997 both). Night 12: the same with the order service container: its unacknowledged messages are redelivered, the primary key absorbs the repeats, and the count must reach 19,994 with 19,994 distinct ids. The last line prints `--command=status`.

## Try it yourself

1. Run the shortest script: `make up db demo-cutover`. Expect three lines: the first and the last end with `legacy path stays: not enough clean nights yet`, and the second with `legacy path may be retired`.
2. Read the exit code of a failing command: `java -jar target/replace-a-file-drop-0.1.0.jar --command=nothing; echo $?` prints `unknown command` and then `2`.
3. See the difference quoting makes: `name="a b"; printf '[%s]\n' $name; printf '[%s]\n' "$name"` prints two lines then one.
4. See a function and arithmetic: `f() { echo $(( $1 * 9997 )); }; f 3` prints `29991`.
5. Run a script with tracing to see every command: `sh -x scripts/demo_cutover.sh 2>&1 | head -30`.

## Mistakes people make

- **Unquoted variables.** `rm -rf $dir/x` with an empty `dir` removes `/x`. Quote, and use `set -u`.
- **Assuming a command failed when it only printed an error.** Look at the exit code (`$?`) or use `set -e`.
- **Pipelines hide failures.** In `a | b` the exit code is `b`'s unless `set -o pipefail` is on (Bash, not POSIX `sh`).
- **Parsing `ls`.** Use globs (`*.sql`) or `find`.
- **Bash-only features in a `#!/bin/sh` script.** Arrays and `[[ ]]` do not exist in POSIX `sh`; this repository's scripts stay within POSIX features.
- **Scripts that depend on the folder you run them from.** The scripts here are written to run from the repository root, which is how `make` runs them.

## Check yourself

1. What does the first line `#!/bin/sh` do? *Tells the system to run the file with the POSIX shell.*
2. What does `set -eu` change? *The script stops at the first failing command and at the first unset variable.*
3. Why does `demo_week.sh` write `|| echo "(exit code $?...)"`? *To print the exit code of an expected failure without stopping the script.*
4. What does `$(( night * 9997 ))` do? *Arithmetic: it computes the expected number of orders.*
5. What does `docker restart` do to the broker in the demo? *Stops and starts it, so the flow's in-flight publishes must be retried.*

## Further reading

- POSIX shell command language (the language of `#!/bin/sh`): https://pubs.opengroup.org/onlinepubs/9699919799/utilities/V3_chap02.html
- GNU Bash reference manual (Bash 3.2 is the macOS default; 5.x elsewhere): https://www.gnu.org/software/bash/manual/bash.html
- `grep`, `sed`, `tr`, `cut` manual pages: `man grep`, `man sed`, `man tr`, `man cut` in any terminal.
