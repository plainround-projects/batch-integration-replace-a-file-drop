# RabbitMQ and Spring AMQP

## What it is

**RabbitMQ** is a **message broker**: a server that accepts messages from programs that produce them (**producers** or **publishers**), stores them safely, and hands them to programs that want them (**consumers**). Producer and consumer never talk to each other; they talk to the broker, so either can be stopped, restarted or slowed down without the other noticing, and the broker keeps the messages in between.

The vocabulary:

- A **queue** is a named line of messages kept by the broker. Messages are taken from the front. A **durable** queue survives a broker restart; a **persistent** message is written to disk, so it survives one too.
- An **exchange** receives published messages and decides which queues get them, using **routing keys**. This project uses the **default exchange** (the nameless one, written `""`): it sends a message to the queue whose name equals the routing key. So publishing with the routing key `retail` puts the message in the queue `retail`.
- A **connection** is the TCP link to the broker; a **channel** is a lightweight session inside a connection, on which all commands are sent.
- **Acknowledgements.** A consumer tells the broker "I handled it" (an **ack**) or "I could not" (a **nack** or **reject**). Until the ack, the broker keeps the message and will deliver it again if the consumer's connection dies: this is **at-least-once delivery**.
- **Publisher confirms.** The same idea in the other direction: after a publish, the broker sends the producer a **confirm** once it has taken responsibility for the message (for a persistent message in a durable queue, after writing it to disk). Without confirms, a publish that vanished in the network would never be noticed.
- A **dead-letter queue** is a queue that receives messages from another queue when they are rejected without being requeued (or expire). It keeps problem messages out of the way and visible.
- **Prefetch** is how many unacknowledged messages the broker sends a consumer at once.

**Spring AMQP** is Spring's client library for RabbitMQ (AMQP is the protocol RabbitMQ speaks). Its pieces used here: `RabbitTemplate` (send and receive), `RabbitAdmin`/`AmqpAdmin` (declare queues, ask for depths), `@RabbitListener` (a method that consumes a queue), `MessageConverter` (objects to bytes and back), and `Declarables` (queues the application creates on startup).

## Why this repository uses it

The new path of the lesson: the flow publishes each valid order to a broker queue, and a separate order service consumes it into a table. The broker decouples the two: the order service can be restarted in the middle of a file (the lesson's step 4) and the messages wait. Confirms and acknowledgements give the guarantee the lesson names: nothing lost, and with an idempotent consumer, nothing doubled.

## Where it appears

| Path | What it does with RabbitMQ |
| --- | --- |
| `${A}/RabbitTopology.java` | Declares the four queues and the JSON converter. |
| `${A}/ConfirmedPublisher.java` | Publishes and waits for the broker's confirm. |
| `${A}/OrderListener.java` | The order service's consumer. |
| `src/main/resources/application.yml` | Connection, confirm and retry settings. |
| `docker-compose.yml` | The `rabbit` service (the broker with the management page). |
| `src/main/java/plainround/orderflow/interfaces/OrderflowRunner.java` | Queue depths for `make status`. |
| `src/test/java/plainround/orderflow/integration/StoresAndBrokerIT.java` | Tests against the real broker. |

## How it works underneath

A published message travels: the client library writes a frame on the channel; the broker routes it through the exchange to the queue; if the message is persistent and the queue durable, the broker writes it to disk; then it sends a confirm back on the same channel. A consumer connected to the queue receives messages up to its prefetch limit; each is held as "unacknowledged" until the consumer acks it. If the consumer's connection closes before the ack, the broker puts the message back at the front of the queue and delivers it again. That redelivery is why consumers must be idempotent.

Spring AMQP's listener container runs consumer threads, converts each message body to an object with the `MessageConverter`, calls your method, and acks if the method returns normally. If the method throws, the container's **retry** policy tries again; when attempts are exhausted the message is **rejected** and, with `default-requeue-rejected: false`, not put back, so the queue's dead-letter setting moves it to another queue instead of looping.

The confirm in Spring AMQP: with `publisher-confirm-type: simple`, the template puts the channel into confirm mode; `waitForConfirmsOrDie(timeout)` blocks until all messages published on that channel since the last wait are confirmed, and throws if one is nacked or the time runs out. Because the wait applies to a channel, the code runs publish and wait inside `template.invoke(...)`, which gives both calls the same channel.

## Walkthrough of this repository's use

```java
package plainround.orderflow.infrastructure.amqp;

import java.util.Map;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The broker's side of the design. Four durable queues: one per order type; {@code dead-letter} for
 * lines the flow refuses (published with their reason); {@code rejected} for orders the service
 * could not process after its retries (the two order queues dead-letter into it through the default
 * exchange).
 */
@Configuration
public class RabbitTopology {

  public static final String DEAD_LETTER = "dead-letter";
  public static final String REJECTED = "rejected";

  @Bean
  Declarables queues() {
    Map<String, Object> toRejected =
        Map.of("x-dead-letter-exchange", "", "x-dead-letter-routing-key", REJECTED);
    return new Declarables(
        order("retail", toRejected),
        order("wholesale", toRejected),
        QueueBuilder.durable(DEAD_LETTER).build(),
        QueueBuilder.durable(REJECTED).build());
  }
```

`@Configuration` makes the class a source of beans. The constants name the two queues that carry problems. The bean `queues()` returns a `Declarables`: a bundle of declarations Spring AMQP sends to the broker at startup (declaring is idempotent: an existing queue with the same settings is left alone). `Map.of("x-dead-letter-exchange", "", "x-dead-letter-routing-key", REJECTED)` holds two queue arguments: a message rejected from this queue is republished to the exchange `""` (the default exchange) with the routing key `rejected`, which lands in the queue `rejected`. Four durable queues are declared: `retail` and `wholesale` (one per order type, both with those arguments), `dead-letter` (for lines the flow refuses) and `rejected` (for orders the service could not process).

```java
  private Queue order(String name, Map<String, Object> arguments) {
    return QueueBuilder.durable(name).withArguments(arguments).build();
  }

  @Bean
  MessageConverter messageConverter() {
    return new Jackson2JsonMessageConverter();
  }
}
```

`order` builds a queue with `QueueBuilder.durable(name).withArguments(arguments)`. The bean `messageConverter` makes `Jackson2JsonMessageConverter` the converter: orders travel as JSON text (see the Jackson note) with the content type `application/json`.

```java
package plainround.orderflow.infrastructure.amqp;

import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import plainround.orderflow.domain.Order;

/**
 * Publishes and waits for the broker's confirmation on a dedicated channel. A nack or a timeout is
 * an exception, so the flow knows the order did not arrive and the idempotent receiver can forget
 * it. Messages are persistent and the queues durable: a confirmed order survives a broker restart.
 */
@RequiredArgsConstructor
public class ConfirmedPublisher {

  private final RabbitTemplate template;
  private final long confirmTimeoutMillis;

  /**
   * Sends the order to the queue of its type; returns when the broker has taken responsibility for
   * it.
   */
  public void publishOrder(Order order, String partnerFile) {
    template.invoke(
        operations -> {
          operations.convertAndSend(
              "",
              order.type().queue(),
              order,
              message -> {
                message.getMessageProperties().setHeader("partnerFile", partnerFile);
                return message;
              });
          operations.waitForConfirmsOrDie(confirmTimeoutMillis);
          return null;
        });
  }
```

`ConfirmedPublisher` publishes an order and waits for the confirm. `template.invoke(operations -> {...})` runs the lambda with a `RabbitOperations` bound to one channel. `operations.convertAndSend("", order.type().queue(), order, postProcessor)` sends: the first argument `""` is the default exchange, the second is the routing key (the queue name `retail` or `wholesale`), the third is the object (converted to JSON), and the fourth is a lambda that adds a header `partnerFile` with the name of the source file before sending. `operations.waitForConfirmsOrDie(confirmTimeoutMillis)` then blocks until the broker confirms (5 seconds by default) and throws an exception on a nack or a timeout; the flow does not remember the order id if this throws. `return null;` is required because the lambda must return a value.

```java
  /** Sends a refused line to the dead-letter queue with the reason, confirmed the same way. */
  public void publishDeadLetter(String body, String reason, String partnerFile) {
    template.invoke(
        operations -> {
          operations.convertAndSend(
              "",
              RabbitTopology.DEAD_LETTER,
              body,
              message -> {
                message.getMessageProperties().setHeader("reason", reason);
                message.getMessageProperties().setHeader("partnerFile", partnerFile);
                return message;
              });
          operations.waitForConfirmsOrDie(confirmTimeoutMillis);
          return null;
        });
  }
}
```

`publishDeadLetter` does the same for a refused line: the body is the original line as text, sent to the queue `dead-letter`, with headers `reason` and `partnerFile`. So a person looking at the queue sees why a line was refused and where it came from.

```java
package plainround.orderflow.infrastructure.amqp;

import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import plainround.orderflow.application.ProcessOrderMessage;
import plainround.orderflow.domain.Order;

/**
 * The order service's consumer. A message that cannot be converted is rejected without requeue at
 * once; one that fails while being processed is retried three times (see {@code application.yml})
 * and then rejected, and the queue's dead-letter setting moves it to {@code rejected} instead of
 * looping for ever.
 */
@Component
@Profile("order-service")
@RequiredArgsConstructor
public class OrderListener {

  private final ProcessOrderMessage process;

  @RabbitListener(queues = {"retail", "wholesale"})
  public void onOrder(Order order) {
    process.handle(order);
  }
}
```

`@Component` registers the class, `@Profile("order-service")` activates it only in the order service process, `@RequiredArgsConstructor` writes the constructor. `@RabbitListener(queues = {"retail", "wholesale"})` makes Spring AMQP start consumers on both queues and call `onOrder` for each message; the JSON body is converted to an `Order` by the converter. `process.handle(order)` stores it (the primary key makes a repeat a no-op). A message that cannot be converted (broken JSON) is rejected at once without requeue; one that fails while being processed is retried and then rejected, and the queue's dead-letter setting moves it to `rejected`.

```yaml
  rabbitmq:
    host: ${RABBIT_HOST:localhost}
    port: ${RABBIT_PORT:56721}
    username: ${RABBIT_USER:orderflow}
    password: ${RABBIT_PASSWORD:}
    publisher-confirm-type: simple  # confirmSelect() on every channel, so the publisher can wait for the broker's confirm
    publisher-returns: true
    template:
      mandatory: true            # an unroutable message is returned, not silently dropped
    listener:
      simple:
        default-requeue-rejected: false # a rejected message goes to the dead-letter queue, not back to the head
        prefetch: 50
        retry:
          enabled: true          # three attempts, then rejected without requeue
          max-attempts: 3
          initial-interval: 500ms
          multiplier: 2.0
```

The settings: `host`, `port`, `username` and `password` from the environment (the compose stack maps the broker to port 56721). `publisher-confirm-type: simple` turns confirm mode on. `publisher-returns: true` with `template.mandatory: true` makes the broker return a message that no queue accepted, instead of silently dropping it. In the `listener.simple` block: `default-requeue-rejected: false` means a rejected message is not put back on the queue; `prefetch: 50` lets the broker send 50 unacknowledged messages at a time; `retry` enables three attempts (`max-attempts: 3`) with a first wait of 500 ms that doubles (`multiplier: 2.0`), after which the message is rejected.

```yaml
  rabbit:
    image: rabbitmq:3.13-management
    hostname: rabbit
    ports:
      - "${RABBIT_PORT:-56721}:5672"
      - "${RABBIT_MANAGEMENT_PORT:-56722}:15672"
    environment:
      RABBITMQ_DEFAULT_USER: ${RABBIT_USER:-orderflow}
      RABBITMQ_DEFAULT_PASS: ${RABBIT_PASSWORD:?copy .env.example to .env first}
    healthcheck:
      test: ["CMD", "rabbitmq-diagnostics", "-q", "ping"]
      interval: 10s
      timeout: 15s
      retries: 30
      start_period: 20s
    volumes:
      - rabbit-data:/var/lib/rabbitmq
```

The broker as a container: the image `rabbitmq:3.13-management` includes the management web page; `hostname: rabbit` fixes the node name (RabbitMQ stores its data under a directory named after it, so a stable name keeps the data between restarts); two ports are published (5672 for AMQP and 15672 for the management page, mapped to 56721 and 56722 by default); the default user and password come from the environment (`${RABBIT_PASSWORD:?copy .env.example to .env first}` refuses to start without one); the health check runs `rabbitmq-diagnostics -q ping`; and the named volume `rabbit-data` keeps queues and persistent messages when the container restarts.

```java
  @Test
  void
      a_confirmed_publish_leaves_a_persistent_message_in_the_queue_of_its_type_and_a_dead_letter_carries_its_reason() {
    try (ConfigurableApplicationContext context = TestStack.start(inbox)) {
      RabbitAdmin admin = context.getBean(RabbitAdmin.class);
      TestStack.purgeQueues(admin);
      ConfirmedPublisher publisher =
          new ConfirmedPublisher(context.getBean(RabbitTemplate.class), 5000);
      publisher.publishOrder(order("O1", "10.00"), "orders-1.csv");
      publisher.publishDeadLetter("O2,C,retail,", "amount missing", "orders-1.csv");
      assertThat(TestStack.depth(admin, "retail")).isEqualTo(1);
      assertThat(TestStack.depth(admin, "wholesale")).isZero();
      Message dead = context.getBean(RabbitTemplate.class).receive("dead-letter", 5000);
      assertThat(dead).isNotNull();
      assertThat(dead.getMessageProperties().getHeaders())
          .containsEntry("reason", "amount missing");
      TestStack.purgeQueues(admin);
    }
  }
```

The test starts the application context, purges the queues, publishes an order and a dead letter with `ConfirmedPublisher`, and checks that exactly one message is in `retail`, none in `wholesale`, and that the dead letter carries the header `reason` equal to `amount missing`.

```java
  @Test
  void
      a_message_that_cannot_be_read_is_rejected_to_the_rejected_queue_and_the_service_keeps_working() {
    try (ConfigurableApplicationContext context = TestStack.start(inbox, "order-service")) {
      RabbitAdmin admin = context.getBean(RabbitAdmin.class);
      RabbitTemplate template = context.getBean(RabbitTemplate.class);
      TestStack.purgeQueues(admin);
      template.send(
          "",
          "retail",
          MessageBuilder.withBody("{not json".getBytes())
              .setContentType("application/json")
              .build());
      Awaitility.await()
          .atMost(Duration.ofSeconds(60))
          .until(() -> TestStack.depth(admin, "rejected") == 1);
      new ConfirmedPublisher(template, 5000).publishOrder(order("O9", "5.00"), "x.csv");
      Awaitility.await()
          .atMost(Duration.ofSeconds(60))
          .until(() -> jdbc.queryForObject("SELECT count(*) FROM orders_events", Long.class) == 1);
      assertThat(TestStack.depth(admin, "retail")).isZero();
      TestStack.purgeQueues(admin);
    }
  }
```

The second test sends a body that is not valid JSON (`{not json`) to the `retail` queue with the `order-service` profile active. The listener cannot convert it, rejects it without requeue, and the dead-letter arguments move it to `rejected`; `Awaitility` waits until that queue holds one message. Then a good order is published and the test waits until the table has one row: the service keeps working after a poison message.

## Try it yourself

1. Start the broker: `make up`. Open the management page at `http://localhost:56722` (user and password are in your `.env`) and look at the Queues tab; the queues appear when an application first connects.
2. See the queues and depths from the command line: `make status` prints `queue retail 0 messages` and the other three.
3. Publish through the flow: `make apps partner`, wait 30 seconds, `make status`. The new path holds the orders and the dead-letter queue holds 3 messages.
4. Look inside a message: in the management page open queue `dead-letter`, "Get messages", and read the `reason` and `partnerFile` headers.
5. Purge the queues: `make purge` runs `rabbitmqctl purge_queue` for the four queues.

## Mistakes people make

- **Publishing without confirms.** A message lost on the way is never noticed.
- **Remembering a message as "sent" before the confirm.** A crash then loses it for good.
- **Requeueing a poison message.** It comes back at once, for ever. Reject without requeue and dead-letter it.
- **Non-durable queues or non-persistent messages.** A broker restart then loses the messages.
- **A consumer that is not idempotent.** At-least-once delivery means the same message can arrive again.
- **A huge prefetch.** One slow consumer holds many messages that others could process.

## Check yourself

1. What does the default exchange do? *Routes a message to the queue whose name equals the routing key.*
2. What does `waitForConfirmsOrDie` do? *Blocks until the broker confirms the published messages on that channel, and throws on a nack or a timeout.*
3. Why `x-dead-letter-routing-key: rejected`? *A message rejected from `retail` or `wholesale` is republished to the queue `rejected`.*
4. When does the broker redeliver a message? *When the consumer's connection closes before it acknowledged.*
5. Why is `default-requeue-rejected` false? *A rejected message must go to the dead-letter queue, not back to the head of the queue.*

## Further reading

- RabbitMQ 3.13 documentation: https://www.rabbitmq.com/docs
- Publisher confirms: https://www.rabbitmq.com/docs/confirms
- Dead letter exchanges: https://www.rabbitmq.com/docs/dlx
- Spring AMQP reference 3.2: https://docs.spring.io/spring-amqp/reference/3.2/
- Spring Boot RabbitMQ settings: https://docs.spring.io/spring-boot/3.5/reference/messaging/amqp.html
