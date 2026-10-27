# 1. The idempotent receiver remembers an order only after the broker has confirmed it

## Context

Spring Integration's `MetadataStoreSelector` (the lesson's tool) accepts a message the first time it sees its key and records the key at that moment, before anything downstream has happened. If the process dies, or the broker refuses the message, after the key is recorded and before the order is published, a resend of the same order is dropped as a duplicate and the order is lost for good. The window is small and real.

## Decision

The filter only looks the order id up in the `JdbcMetadataStore` (`orderMemory.get(id) == null`); the order is published with `waitForConfirmsOrDie`; only then is the id recorded (`putIfAbsent`). A crash between the confirmation and the record publishes the order a second time on restart, and the order service absorbs the repeat (ADR 2). The memory is a table, so it survives a restart. A bad line is never recorded as an order, so a corrected resend of the same order id is accepted. A bad line is remembered separately, by a hash of its text, after its dead-letter message has been confirmed: the partner resends whole files, and an unchanged bad line must not make the dead-letter queue grow, while a corrected line is a different line and goes through as an order.

## Alternatives

- `MetadataStoreSelector` plus an advice that forgets the key when the publish throws: handles an exception, not a killed process.
- No memory at all: the order service alone makes duplicates harmless, but every resent file would flood the broker.

## Consequences

- At the flow, the guarantee is at-least-once per order, and "at most once per file resend" is a performance property, not a correctness one.
- One lookup and one insert per order in PostgreSQL (about 20,000 statements for a 10,000-order file); the batch size could be raised with a bulk lookup if that becomes the cost.
