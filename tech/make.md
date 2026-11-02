# Make

## What it is

`make` is a command-line tool that runs named recipes from a file called `Makefile`. Each recipe is a **target** with the shell commands that produce it. It was invented to rebuild only what is out of date, but it is also widely used as a simple, shared list of project commands: `make test`, `make up`, `make down`. A person who has never seen the project can type `make help` and see what is possible.

The syntax of a rule:

```text
target: dependency-1 dependency-2   ## optional comment
<TAB>command
<TAB>command
```

The commands must start with a **Tab** character (not spaces). Before running a target, `make` first makes each **dependency** (another target, or a file that must be up to date). A target that is not a real file name, such as `test`, is declared in `.PHONY` so make never confuses it with a file called `test`.

## Why this repository uses it

The lesson has many steps, each with its own long command line (start the broker, the database and the SFTP server; load the schema; start the flow and the order service; drop a file; run the legacy loader; reconcile; restart things on purpose; test; audit). The Makefile gives each a short name so the README can say `make demo-restart` and a reader never has to learn the long form. `make help` prints the list.

## Where it appears

| Path | What it does with Make |
| --- | --- |
| `Makefile` | Every target the README tells you to run. |
| `.github/workflows/ci.yml` | Calls `make lint`, `make test`, `make audit` and the integration targets, so CI and a laptop run identical commands. |
| `scripts/demo_week.sh`, `demo_restart.sh`, `demo_cutover.sh` | Started by `make demo-week`, `make demo-restart` and `make demo-cutover`. |

## How it works underneath

`make` reads the whole `Makefile`, builds a graph of targets and dependencies, then runs the commands of the target you asked for after those of its dependencies. For every command line it starts a **new shell**, so a variable set on one line is gone on the next unless you join the lines with `;` and a backslash `\`. A command line that starts with `@` is not echoed; one that starts with `-` ignores failures.

Variables: `NAME := value` assigns once when the file is read; `NAME ?= value` assigns only if `NAME` has no value yet (so the environment or the command line can override it: `make partner NIGHT=3`); `$(NAME)` reads it. In a recipe `$$` is how you write a single `$` for the shell (`$$x` becomes `$x`). `-include .env` reads another file of assignments if it exists, and `export` with no names passes every variable to the commands as environment variables. A value given on the make command line (`make partner NIGHT=3`) beats the file; a value in the Makefile beats the environment unless the variable was written with `?=`.

## Walkthrough of this repository's use

```make
# make up | db | apps | partner | status | reconcile | demo-* | test | lint | down   (make help lists all)
MVN := ./mvnw -q -B
JAR := target/replace-a-file-drop-0.1.0.jar
NIGHT ?= 1

POSTGRES_USER ?= orderflow
POSTGRES_DB ?= orderflow

-include .env
export

PSQL := docker compose exec -T postgres psql -U $(POSTGRES_USER) -d $(POSTGRES_DB) -v ON_ERROR_STOP=1 -q
APP := java -jar $(JAR)

.PHONY: help env up db apps build partner partner-again legacy-load reconcile status queries reset purge demo-week \
        demo-restart demo-cutover logs health lint format test test-integration test-e2e audit check docker-build \
        down clean
```

Line 2, `MVN := ./mvnw -q -B`, is the Maven command: the wrapper, `-q` (quiet) and `-B` (batch mode: no colors or progress bars, so logs are clean). Line 3 is the name of the jar the build produces. Line 4, `NIGHT ?= 1`, is the number of the night whose file to drop; `?=` assigns only when the variable has no value yet, so `make partner NIGHT=3` overrides it. Lines 6 and 7 give the database user and name defaults. Line 9, `-include .env`, reads `.env` (made by `make env` from `.env.example`) if it exists; the leading dash means "no error if the file is missing". Line 10, `export`, passes every variable to every command, so the Java program sees `DB_PASSWORD` and the rest. Line 12 defines `PSQL`, the command that runs `psql` (PostgreSQL's command-line client) inside the container: `docker compose exec -T postgres psql -U ... -d ... -v ON_ERROR_STOP=1 -q`. `exec` runs a command in a running container; `-T` turns off the pseudo-terminal so input can be piped in; `-v ON_ERROR_STOP=1` makes `psql` stop at the first SQL error; `-q` is quiet. Line 13 is the command that runs the jar. Lines 15 to 17 list the **phony targets** (names that are commands, not files); the backslash continues the line.

```make
help: ## list the targets
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | sed -E 's/:.*## /\t/' | sort

env:
	@[ -f .env ] || { cp .env.example .env; echo "created .env from .env.example"; }

up: env ## start PostgreSQL, RabbitMQ and the SFTP server
	docker compose up -d --wait postgres rabbit sftp

db: ## apply sql/001_schema.sql and the migrations (history of reconciliations)
	$(PSQL) < sql/001_schema.sql
	@for file in sql/migrations/*.sql; do echo "applying $$file"; $(PSQL) < $$file; done

$(JAR): pom.xml $(shell find src/main sql -type f)
	$(MVN) -DskipTests package

build: $(JAR) ## build the jar

apps: ## build the image and start the flow and the order service as containers (profile apps)
	docker compose --profile apps up -d --build flow order-service
```

`help` (19 to 20) prints every target that has a `##` comment: `grep` selects lines that look like `name: ... ## text`, `sed` replaces the `: ... ## ` part with a Tab, `sort` orders them; `$(MAKEFILE_LIST)` is the list of makefiles read. `env` (22 to 23) copies `.env.example` to `.env` only if `.env` does not exist (`[ -f .env ] || { ...; }`: if the test fails, run the block). `up` (25 to 26) depends on `env` and runs `docker compose up -d --wait postgres rabbit sftp`: start those three services in the background (`-d`) and wait until their health checks pass; naming the services leaves out the two application containers, which are in the `apps` profile. `db` (28 to 30) loads the schema by redirecting `sql/001_schema.sql` into `psql` with `<`, then loops over the migration files: `for file in sql/migrations/*.sql; do echo "applying $$file"; $(PSQL) < $$file; done` (the double dollar is the shell's own `$`; make would otherwise try to expand it). `$(JAR)` (32 to 33) is a **file target**: the jar depends on the pom and on every file under `src/main` and `sql` (`$(shell find ... -type f)` runs a command while reading the file), so make rebuilds the jar only when one of those is newer. `build` (35) depends on it. `apps` (37 to 38) builds the image and starts the flow and the order service as containers: `docker compose --profile apps up -d --build flow order-service`.

```make
partner: build ## drop a file of 10,000 orders on the SFTP server (NIGHT=1; the seed is the night number)
	$(APP) --command=partner --night=$(NIGHT) --seed=$(NIGHT)

partner-again: build ## resend the same file under a new name: no new order may appear
	$(APP) --command=partner --night=$(NIGHT) --seed=$(NIGHT) --name=orders-$(NIGHT)-again.csv

legacy-load: build ## the stand-in for the nightly batch: load the files it has not loaded yet into orders_batch
	$(APP) --command=legacy-load

reconcile: build ## the morning report: orders only in batch, only in events, different, same
	$(APP) --command=reconcile

status: build ## files on SFTP, queue depths, rows per path, ids remembered
	$(APP) --command=status

queries: ## run sql/002_queries.sql
	$(PSQL) < sql/002_queries.sql

reset: ## forget both paths' orders, the receiver's memory and the loaded files, and empty the partner's folder
	$(PSQL) < sql/reset.sql
	docker compose exec -T sftp sh -c 'rm -f /home/partner/upload/*.csv'

purge: ## empty the broker's queues
	docker compose exec -T rabbit rabbitmqctl purge_queue retail
	docker compose exec -T rabbit rabbitmqctl purge_queue wholesale
	docker compose exec -T rabbit rabbitmqctl purge_queue dead-letter
	docker compose exec -T rabbit rabbitmqctl purge_queue rejected
```

`partner` depends on `build` and runs the program with `--command=partner --night=$(NIGHT) --seed=$(NIGHT)`: it generates the night's file and uploads it. `partner-again` does the same under a different file name (`--name=orders-$(NIGHT)-again.csv`): the lesson's resend test. `legacy-load` runs the stand-in for the old nightly batch. `reconcile` runs the morning comparison. `status` prints files, queue depths and row counts. `queries` runs `sql/002_queries.sql`. `reset` forgets both paths' orders and empties the partner's folder (`sh -c 'rm -f /home/partner/upload/*.csv'` runs inside the SFTP container; the single quotes keep the star for that shell, not this one). `purge` runs `rabbitmqctl purge_queue` for each of the four queues.

```make
demo-week: build ## steps 3 and 5: a week of files through both paths, reconciled; a planted difference is caught
	sh scripts/demo_week.sh

demo-restart: build ## step 4: restart the broker and then the order service in the middle of a file; nothing lost or doubled
	sh scripts/demo_restart.sh

demo-cutover: build ## step 6: the retirement condition as a number, from the recorded reconciliations
	sh scripts/demo_cutover.sh

logs: ## the flow's and the order service's logs (needs make apps)
	docker compose logs --tail=40 flow order-service

health: build ## exit 0 when the database, the broker and the SFTP folder are reachable
	$(APP) --command=health
```

`demo-week`, `demo-restart` and `demo-cutover` build the jar and hand over to a script in `scripts/` (the Bash note). `logs` shows the last 40 lines of the two application containers. `health` runs the health command, which exits 0 when the database, the broker and the SFTP folder answer.

```make
lint: ## spotless (google-java-format) check and the compiler's -Xlint:all -Werror
	$(MVN) spotless:check compile

format: ## apply google-java-format
	$(MVN) spotless:apply

test: ## unit and architecture tests with coverage (no services needed); the domain and application stay at 95%
	$(MVN) verify -DskipITs

test-integration: ## the adapters against the running services (make up and make db first)
	$(MVN) test-compile failsafe:integration-test failsafe:verify -Dfailsafe.groups=integration

test-e2e: ## the lesson's scenario in one JVM against the running services (make up and make db first)
	$(MVN) test-compile failsafe:integration-test failsafe:verify -Dfailsafe.groups=e2e

audit: ## check the dependencies against the OSS Index vulnerability database
	$(MVN) org.sonatype.ossindex.maven:ossindex-maven-plugin:audit

check: lint test ## everything that needs no services

docker-build: ## build the container image
	docker build -t batch-integration-replace-a-file-drop:dev .

down: ## stop everything and delete the data
	docker compose --profile apps down -v

clean: down
	rm -rf target data logs
```

`lint` runs the Spotless check and the compiler. `format` applies the formatter. `test` runs `verify` with `-DskipITs`, so the unit tests and the JaCoCo check run but the integration tests are skipped. `test-integration` and `test-e2e` run only the Failsafe goals for the chosen tag group. `audit` runs the OSS Index plug-in. `check` depends on `lint` and `test`. `docker-build` builds the image. `down` stops everything including the application profile and deletes the volumes (`-v`). `clean` depends on `down`, then removes the build output, the data folder and the logs.

## Try it yourself

1. List the targets: `make help`. Expect about 25 lines of `name<TAB>description`.
2. See what a target would do without doing it: `make -n partner NIGHT=3`. The `-n` flag prints the commands.
3. Override a variable: `make -n partner NIGHT=3` shows `--night=3 --seed=3`.
4. Watch the file dependency: run `make build` twice; the second prints nothing because the jar is up to date. `touch sql/reconcile.sql` and run it again; it rebuilds.
5. Check the tab rule: `cat -A Makefile | sed -n 20p` shows `^I` at the start of the command line (that is a Tab).

## Mistakes people make

- **Spaces instead of a Tab** before a command: make stops with "missing separator".
- **A variable set on one line and used on the next.** Each line is a separate shell; join them or use a script.
- **Forgetting `.PHONY`.** A file named like a target makes make say "nothing to do".
- **`$` in recipes.** Write `$$` for the shell's `$`, or make will try to expand it itself.
- **Environment variable overridden by an included file.** `-include .env` assigns variables that beat the same names in the environment; pass overrides on the make command line instead.

## Check yourself

1. What does `-include .env` do when `.env` is missing? *Nothing; the dash suppresses the error.*
2. Why `$$file` and not `$file`? *make would try to expand `$f`; `$$` passes a single `$` to the shell.*
3. What does `?=` do? *Assigns only if the variable has no value yet, so users can override.*
4. Why does `make up` list `postgres rabbit sftp`? *To start only the infrastructure; the application containers belong to the `apps` profile.*
5. When is the jar rebuilt? *When `pom.xml` or a file under `src/main` or `sql` is newer than the jar.*

## Further reading

- GNU Make manual (the macOS default `make` is GNU Make 3.81; the manual applies): https://www.gnu.org/software/make/manual/make.html
- Phony targets: https://www.gnu.org/software/make/manual/make.html#Phony-Targets
- Variables: https://www.gnu.org/software/make/manual/make.html#Using-Variables
