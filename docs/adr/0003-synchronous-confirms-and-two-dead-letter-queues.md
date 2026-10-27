# 3. A confirm per publish, durable queues, and two dead-letter queues

## Context

A broker that accepts a message into a socket buffer has not necessarily stored it. The flow must know that an order is safe before it forgets it, and a message that can never be processed must not loop.

## Decision

`ConfirmedPublisher` publishes persistent messages to durable queues on a dedicated channel and waits for the broker's confirmation (`waitForConfirmsOrDie`, five seconds); a nack or a timeout is an exception, so the order is not recorded and the file is not marked done. Lines the flow refuses go to the `dead-letter` queue with the reason in a header. Orders the service cannot process are retried three times (exponential back-off) and then rejected without requeue; the order queues' dead-letter setting moves them to `rejected`. A message that cannot even be converted is rejected at once.

## Alternatives

- Asynchronous confirms with correlation: higher throughput (a batch of publishes per confirm); more state to get right. The synchronous version is about 1 ms per publish on a local broker, which publishes a 10,000-order file in a few seconds; the ARCHITECTURE.md limits section names the upgrade.
- One dead-letter queue for both: mixes "the partner sent garbage" with "we could not process a valid order", which have different owners.

## Consequences

- `docker restart` of the broker in the middle of a file loses nothing (queues and messages are durable, unconfirmed publishes fail and are retried by the next poll).
- `status` prints the depth of the four queues; a growing `rejected` queue is the signal for the on-call.
