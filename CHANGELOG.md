# Changelog

All notable changes to this project are recorded here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).

## [0.1.0] - 2026-10-27

### Added

- A Spring Integration flow that polls an SFTP folder, fetches each file once, splits it into lines, drops the header, translates each line into an order or a rejection, routes bad lines to a dead-letter queue with their reason, and publishes good orders to RabbitMQ with publisher confirms.
- An idempotent receiver kept in a JDBC metadata store that records an order only after the broker has confirmed it, so a crash publishes an order twice rather than losing it, and an order service that stores each order once with `ON CONFLICT DO NOTHING`.
- Durable queues, persistent messages, three delivery attempts and a rejected-orders queue; a restart of the broker or the order service in the middle of a file loses and duplicates nothing.
- The legacy batch loader running into its own table, a morning reconciliation (a full outer join by order id, amount, status, customer and type), its recorded history, and a retirement rule of 14 clean nights in a row.
- A cutover plan with a rollback and the three signals to watch.
- SQL for the metadata store, both order paths, the reconciliation history and the lesson's queries.
- Layered packages with the dependency rule enforced by ArchUnit; unit, integration and end-to-end tests with 95 percent line coverage required on domain and application; google-java-format, `-Xlint:all -Werror`, a Dockerfile, a compose file and a CI workflow.
