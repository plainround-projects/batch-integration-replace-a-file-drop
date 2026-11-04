# batch-integration-replace-a-file-drop

A partner's nightly file of orders on an SFTP server, replaced by a Spring Integration flow that splits it into order messages, sends the bad lines to a dead-letter queue, removes duplicates, and publishes to RabbitMQ with confirmations, run side by side with the old batch loader and reconciled every morning until a number says the old path can go.

| | |
| --- | --- |
| **Lesson** | `/prep/batch-integration/project-replace-a-file-drop`, "The partner's orders as a message flow" (`data-structure-explainer/src/content/tracks/batch-integration/15-project-replace-a-file-drop.md` in the Plainround app) |
| **Planned hours** | 14 |
| **Level** | Advanced |
| **Architecture** | [ARCHITECTURE.md](ARCHITECTURE.md), decisions: [docs/adr](docs/adr), the cutover: [docs/CUTOVER.md](docs/CUTOVER.md) |

## Architecture

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/diagrams/architecture-dark.svg">
  <img alt="The partner file feeds a Spring Integration flow of adapter, splitter, translator, router and idempotent receiver, then a confirmed publish into RabbitMQ and the order service, and the legacy batch loader in parallel; reconciliation compares orders_events with orders_batch." src="docs/diagrams/architecture.svg">
</picture>

Notice the two paths from one file, and that duplicates are removed twice: in the receiver before the broker and in the orders table after it.

## How one run flows

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/diagrams/flow-dark.svg">
  <img alt="One partner file goes through adapter, splitter, translator, router and receiver to the broker, the order service and PostgreSQL, then the legacy loader and the reconciliation that counts clean runs." src="docs/diagrams/flow.svg">
</picture>

The run ends at 14 clean runs, when the legacy path may be retired; the amber rows show a bad line sent to dead-letter and an order id the receiver has already seen.

## Prerequisites

| Tool | Version | Used for |
| --- | --- | --- |
| Java (JDK) | 21 or newer (tested with 25; the code targets 21 and CI uses 21) | build and run |
| Docker with Compose v2 | 24 or newer | PostgreSQL 16, RabbitMQ 3.13, an SFTP server (`atmoz/sftp`) |
| GNU Make | 3.81 or newer | every command below |
| Maven | none to install, the wrapper `./mvnw` is in the repository | build |
| Libraries | Spring Boot 3.5.x with Spring Integration (SFTP, JDBC), Spring AMQP, Lombok, Result4J, Instancio, Awaitility, ArchUnit, JaCoCo, Spotless | pinned in `pom.xml` |

No cloud account. Passwords for the database, the broker and the SFTP server are read from the environment: `make up` copies `.env.example` to `.env` the first time (throwaway values for containers that live only on this machine). Ports 55561 (PostgreSQL), 56721 and 56722 (RabbitMQ and its management page) and 56222 (SFTP) must be free; `.env` changes them.

## Setup, run, test, stop

```bash
make up                 # PostgreSQL, RabbitMQ and the SFTP server in containers, .env from .env.example
make db                 # sql/001_schema.sql and the migrations
make partner            # a file of 10,000 orders (a few planted bad lines) dropped on the SFTP server
make apps               # builds the image and starts the flow and the order service as containers
make status             # files on SFTP, queue depths, rows per path, ids remembered
make reconcile          # the morning report: only in batch, only in events, different, same
make test               # unit and architecture tests with coverage (no services needed)
make lint               # google-java-format check and the compiler's -Xlint:all -Werror
make test-integration   # the stores and the broker against the real services (database orderflow_test)
make test-e2e           # the lesson's week, six ordered phases, against the real services
make audit              # known-vulnerability check of the dependencies (OSS Index)
make down               # stops everything and deletes the data
```

The lesson's steps: `make partner` and `make apps` (steps 2 and 3), `make partner-again` (the same file twice), `make demo-restart` (step 4), `make demo-week` (step 5, a week of files and a planted difference), `make demo-cutover` (step 6, the retirement condition as a number). Also `make legacy-load`, `make queries`, `make purge`, `make reset`, `make health`, `make logs`; `make help` lists every target. The log (JSON) goes to standard error; reports go to standard output.

## The SQL

Every statement the project runs against PostgreSQL is a file under [sql/](sql/), run in this order:

| File | Kind | What it holds | Run by |
| --- | --- | --- | --- |
| [001_schema.sql](sql/001_schema.sql) | DDL | `INT_METADATA_STORE` (Spring Integration's table, here the receiver's and the file memory), `orders_batch` (the old path), `orders_events` (the new path), `legacy_loaded_file` | `make db` |
| [migrations/001_reconciliation_history.sql](sql/migrations/001_reconciliation_history.sql) | DDL | `reconciliation_run`, one row per morning, the history the retirement rule reads | `make db` |
| [reconcile.sql](sql/reconcile.sql) | query | the four counts in one `FULL OUTER JOIN` of the two tables by order id | `make reconcile` |
| [reconcile_details.sql](sql/reconcile_details.sql) | query | the ids behind any difference | `make reconcile` |
| [002_queries.sql](sql/002_queries.sql) | queries | counts per path and per file, duplicates, the receiver's memory | `make queries` |
| [reset.sql](sql/reset.sql) | DML | forget both paths' orders, the receiver's memory and the loaded files | `make reset` |

## Done when: the lesson's checklist and the proof for each line

Output below is from this repository on a Mac laptop with PostgreSQL, RabbitMQ and an SFTP server in containers and the flow and the order service as containers too (`make up db apps`, then the commands in the table). Numbers such as the 9,997 come from the planted data: each file has a few bad lines (3 here) and a repeated order id, so a file of about 10,000 lines holds 9,997 distinct valid orders.

| # | Done when (from the lesson) | Command | What proves it |
| --- | --- | --- | --- |
| 1 | Each pattern on the diagram has one sentence on why it is there, and the diagram shows where duplicates are removed. | read [ARCHITECTURE.md](ARCHITECTURE.md), "Why each pattern is there" | A table with one sentence for each of the ten boxes (one, the Content Enricher, is marked "not used" with its reason), and the two places where duplicates are removed are in bold: the Idempotent Receiver before the broker and the Idempotent Consumer, `ON CONFLICT DO NOTHING`, in the order service. |
| 2 | A file of 10,000 orders becomes 10,000 messages minus the planted bad lines, which sit in the dead-letter channel with their reason. | `make partner`, `make status` | `uploaded orders-1.csv with 9997 valid distinct orders`, then `queue dead-letter  3 messages` and `orders_batch=0 orders_events=9997 seen_orders=9997`. The flow logs each refused line with its reason: `DEAD LETTER: N11X999001,C0001,retail, reason=amount missing`. |
| 3 | Sending the same file twice produces each order once, and the dead-letter channel does not grow. | `make partner-again`, `make status` | The same file under a new name, `orders-1-again.csv`: after it was read, `orders_events=9997 seen_orders=9997` and `queue dead-letter  3 messages`, as before. The three refused lines are remembered byte for byte and are not sent again. |
| 4 | Restarting the broker or the order service during a file produces no lost and no duplicate orders. | `make demo-restart` | Night 11: the broker is restarted four seconds after the upload, while the flow is publishing: `orders on the new path: 9997 (expected 9997), distinct: 9997`. Night 12: the order service is restarted while it is consuming: `orders on the new path: 19994 (expected 19994), distinct: 19994`. The first attempt at this demo found a defect (below). |
| 5 | A week of simulated files reconciles with zero differences, and a planted difference shows up in the report. | `make demo-week` | Seven nights of files (69,979 distinct orders) through the new path, then the legacy batch loads the same files: `only_in_batch 0, only_in_events 0, different 0, same 69979`. Then one amount is changed in transit: `different 1`, `N03O000042 different (batch 423.03/RECEIVED, events 424.03/RECEIVED)`, and the command exits with code 1. |
| 6 | The plan has a rollback step and a condition for retiring the old batch job, based on the reconciliation results. | read [docs/CUTOVER.md](docs/CUTOVER.md), the plan and its "Rollback" section; `make demo-cutover` | The condition is a number, 14 clean nights in a row (`ORDERFLOW_CLEAN_NIGHTS_TO_RETIRE`). The demo records 13 clean nights, `legacy path stays: not enough clean nights yet`; the 14th, `legacy path may be retired`; then a night with one difference resets the count: `legacy path stays: not enough clean nights yet`. |

**The defect the restart demo found.** The first full attempt restarted the broker four seconds into a file and ended with 1,069 of 9,997 orders and a file that was never read again: creating a channel failed with `AmqpConnectException: Connection refused`, the splitter threw, the rest of the file was never published, and Spring Integration does not roll the file filter back after a failure downstream (its reference says so). `ConfirmedPublisher` now tries again every two seconds for up to five minutes while the broker is away, and `ConfirmedPublisherTest` covers the retry. A broker away for longer than five minutes still ends the file early; ARCHITECTURE.md lists that under the limits.

Also run: `make test` (32 unit and architecture tests), `make test-integration` (6, the adapters against the real broker, database and SFTP server), `make test-e2e` (6, the lesson's six phases in order against the running stack), `make lint` and `make audit`.

## Stretch goals

| Stretch (from the lesson) | Built? |
| --- | --- |
| Add an aggregator that groups an order's lines into one message with a completion rule and a timeout. | No. ARCHITECTURE.md, "How to extend it". |
| Use a claim check for orders with large attachments, so the broker carries only a reference. | No. |
| Replace the SFTP polling with a partner API that posts orders, behind the same flow. | Designed, not built: [docs/CUTOVER.md](docs/CUTOVER.md) step 1 adds an input to the same `valid` channel; the receiver, the broker and the service do not change. |

## What is simplified for teaching

Only what the lesson scopes out, plus where this build differs from the lesson's text:

- **The idempotent receiver does not use `MetadataStoreSelector`.** The lesson's hint records an order id before the order is published; a crash between the two loses the order for good. This build looks the id up, publishes with a confirm, and records it after ([ADR 1](docs/adr/0001-remember-the-order-after-the-broker-confirms.md)). It is a deliberate change from the lesson, with a test.
- **The partner is a container.** A real partner means keys instead of a password, a known-hosts check and a firewall allow-list.
- **One flow instance, synchronous confirms** (about a millisecond per publish locally); ARCHITECTURE.md names the upgrade.
- **Throwaway credentials in `.env`, plain-text connections, no TLS** to the broker or the database.
- **The week of files is simulated** by generating seven files with different seeds and loading them in one session; the rule for retiring the old path counts the nights it is given.

## Project structure

```text
src/main/java/plainround/orderflow/
  domain/            Order, OrderType, Rejection, OrderParser (a line to a Result), ReconciliationReport, RetirementPolicy.
                     Plain Java, no framework. Depends on nothing.
  application/       ProcessOrderMessage, ReconcileOrders, GeneratePartnerFile (use cases) and the ports OrderStore,
                     ReconciliationSource, ReconciliationHistory, PartnerDrop. Depends on domain only.
  infrastructure/    sftp/ (the partner drop), amqp/ (topology, confirmed publishing, the order listener), jdbc/ (the stores,
                     the reconciliation, the legacy loader), flow/ (the Spring Integration flows and counters).
                     Depends on application and domain.
  interfaces/        the command-line runner (partner, legacy-load, reconcile, status, health, serve) and the composition root.
                     Nothing depends on it.
src/test/java/       unit, architecture (ArchUnit), integration/ (needs the services) and e2e/ (needs the services)
sql/                 001_schema.sql, migrations/, reconcile*.sql, 002_queries.sql, reset.sql
docs/                adr/ (five decisions) and CUTOVER.md
scripts/             demo_week.sh, demo_restart.sh, demo_cutover.sh
docker-compose.yml   PostgreSQL 16, RabbitMQ 3.13, SFTP; the flow and the order service under the profile "apps"
Dockerfile           multi-stage build, non-root user, pinned base image
```

`ArchitectureTest` enforces the dependency rule (domain depends on nothing; application on domain; infrastructure and interfaces on both; nothing on interfaces) and bans Spring from `domain` and `application`.

## Author and license

Written by Sumit Srivastava. Released under the MIT License, see [LICENSE](LICENSE). Changes are in [CHANGELOG.md](CHANGELOG.md).
