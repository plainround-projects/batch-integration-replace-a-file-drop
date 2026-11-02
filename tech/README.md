# Technology notes: replacing a file drop

These notes teach every technology used in this repository, from zero, through the repository's own files. You do not need to know any of them before you start. Each note explains the idea, shows the repository's own lines, explains them one by one, gives commands to try, lists common mistakes and ends with five questions.

## The files

| File | Role in this repository |
| --- | --- |
| [java.md](java.md) | The language: the parser, the order types, the use cases and their ports. |
| [spring-integration.md](spring-integration.md) | The message flow: SFTP adapter, splitter, filter, translator, router, dead letters, idempotent receiver. |
| [rabbitmq-spring-amqp.md](rabbitmq-spring-amqp.md) | The broker: queues, confirms, acknowledgements, dead-letter queues, the consumer. |
| [sftp.md](sftp.md) | The partner's file drop and the client that reads and writes it. |
| [csv-format.md](csv-format.md) | The partner's file format and its ugly cases. |
| [jackson-json.md](jackson-json.md) | How an order becomes the body of a message. |
| [spring-boot.md](spring-boot.md) | The application framework: start-up, profiles, beans, settings, the command line. |
| [bean-validation.md](bean-validation.md) | Rules on the settings (`@Min`, `@NotBlank`). |
| [lombok.md](lombok.md) | Generated constructors and loggers. |
| [result4j.md](result4j.md) | A value that is either a success or an error: how a line is accepted or refused. |
| [jdbc.md](jdbc.md) | How Java talks to the database, the idempotent insert, the transaction per file. |
| [postgresql.md](postgresql.md) | The database and every `.sql` file, including the reconciliation query. |
| [slf4j-logback.md](slf4j-logback.md) | Logging as JSON lines on standard error. |
| [junit.md](junit.md) | Unit, integration and end-to-end tests, and every test file. |
| [assertj.md](assertj.md) | The assertion style used in the tests. |
| [archunit.md](archunit.md) | The test that enforces the dependency rule. |
| [awaitility.md](awaitility.md) | Waiting for asynchronous work in tests. |
| [instancio.md](instancio.md) | Random test data (declared, not used). |
| [maven.md](maven.md) | The build tool and `pom.xml`. |
| [maven-wrapper.md](maven-wrapper.md) | `mvnw`, which fetches the right Maven. |
| [jacoco.md](jacoco.md) | The coverage gate. |
| [spotless.md](spotless.md) | The code formatter check. |
| [ossindex-audit.md](ossindex-audit.md) | The dependency vulnerability audit. |
| [make.md](make.md) | The `Makefile`: every `make` command. |
| [bash.md](bash.md) | The shell scripts under `scripts/`: the week, the restart and the cutover demos. |
| [env-files.md](env-files.md) | `.env` and `.env.example`. |
| [editorconfig.md](editorconfig.md) | `.editorconfig`: the formatting rules every editor follows. |
| [docker.md](docker.md) | The `Dockerfile` and `.dockerignore`. |
| [docker-compose.md](docker-compose.md) | `docker-compose.yml`: PostgreSQL, RabbitMQ, SFTP and the two application containers. |
| [github-actions.md](github-actions.md) | The CI workflow. |
| [yaml.md](yaml.md) | The file format of the settings, compose and workflow files. |

## The order to read them in

1. The language: [java.md](java.md).
2. The input: [csv-format.md](csv-format.md), then [sftp.md](sftp.md).
3. The design: [spring-integration.md](spring-integration.md), then [rabbitmq-spring-amqp.md](rabbitmq-spring-amqp.md) and [jackson-json.md](jackson-json.md).
4. The frameworks: [spring-boot.md](spring-boot.md), then [lombok.md](lombok.md), [result4j.md](result4j.md) and [bean-validation.md](bean-validation.md).
5. The data: [postgresql.md](postgresql.md), then [jdbc.md](jdbc.md).
6. The build and the commands: [maven.md](maven.md), [maven-wrapper.md](maven-wrapper.md), [make.md](make.md), [bash.md](bash.md).
7. Watching it run: [slf4j-logback.md](slf4j-logback.md), [yaml.md](yaml.md).
8. Packaging and delivery: [docker.md](docker.md), [docker-compose.md](docker-compose.md), [env-files.md](env-files.md), [editorconfig.md](editorconfig.md), [github-actions.md](github-actions.md), [spotless.md](spotless.md), [jacoco.md](jacoco.md), [ossindex-audit.md](ossindex-audit.md).
9. The tests: [junit.md](junit.md), [assertj.md](assertj.md), [archunit.md](archunit.md), [awaitility.md](awaitility.md), [instancio.md](instancio.md).

## If you have ten minutes

Read [spring-integration.md](spring-integration.md) (the flow, and why the order id is remembered only after the broker's confirmation), [rabbitmq-spring-amqp.md](rabbitmq-spring-amqp.md) (confirms, acknowledgements, dead letters) and [postgresql.md](postgresql.md) (the two tables and the reconciliation query that decides when the old path can go).

## A note on what is not shown

The sample file `.env.example` holds throwaway sample credentials for the local database container. [env-files.md](env-files.md) explains the mechanism and shows where every variable is used, without printing the sample values.
