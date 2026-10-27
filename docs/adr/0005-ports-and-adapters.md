# 5. Ports and adapters, with the dependency rule enforced by a test

## Context

The rules (what is a valid order, what a reconciliation means, when the legacy path may go) should be testable without a broker or an SFTP server.

## Decision

Four layers in `plainround.orderflow`: `domain` (orders, the parser that returns a Result4J `Result`, the report, the retirement policy), `application` (the order service's use case, the reconciliation, the partner file generator, with their ports), `infrastructure` (Spring Integration flows, Spring AMQP, JDBC, SFTP), `interfaces` (the command-line runner and the composition root). ArchUnit enforces the layers and bans Spring from `domain` and `application`.

## Consequences

- Every validation rule and the cutover policy are unit-tested in milliseconds; the flow and the order service are tested together against real SFTP, RabbitMQ and PostgreSQL.
