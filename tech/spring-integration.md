# Spring Integration 6.5

## What it is

**Spring Integration** is a library for building programs out of small steps that pass **messages** to each other, the way a factory conveyor belt passes parts from station to station. Each station does one job and knows nothing about the others. It implements a catalog of well-known designs called the **Enterprise Integration Patterns** (a book by Gregor Hohpe and Bobby Woolf): named solutions to recurring problems in connecting systems, such as "split a big message into pieces" or "send a message that cannot be understood to a special place".

The vocabulary, from the ground up:

- A **message** is an envelope: a **payload** (the thing you care about: here a line of text, an `Order` or a `Rejection`) and **headers** (named labels added to the envelope: here `file_name`, the name of the file the line came from). Messages are immutable; every step that changes something creates a new message.
- A **channel** is the pipe between two steps. A step puts a message into a channel; the next step takes it out. A **direct channel** (the default here) hands the message to the next step on the same thread at once, like a function call. A **queue channel** would hold messages until someone takes them.
- An **endpoint** is a step attached to a channel. Kinds used here: a **transformer** (turns a message into another one), a **filter** (lets a message pass or not), a **splitter** (turns one message into many), a **router** (sends a message to one of several channels depending on its content) and a **service activator** (calls ordinary code with the message; the `handle` method of the DSL).
- A **channel adapter** connects the flow to the outside world. An **inbound** adapter brings data in (here: the SFTP adapter that fetches files); an **outbound** adapter sends data out.
- A **poller** is a timer that asks a source "anything new?" at a fixed interval. Pollers are how a flow watches a folder.
- The **Java DSL** (domain-specific language) is a fluent way to write a flow in Java: `IntegrationFlow.from(source).split(...).filter(...).transform(...).handle(...).get()` reads from the source to the end, one step per call.

## Why this repository uses it

The lesson replaces a nightly file drop with a flow that reacts to the file within seconds: fetch the file once, split it into lines, drop the header, turn each line into an order, send bad lines to a dead-letter place with the reason, drop orders already seen, and publish new ones to the broker with confirmation. Each of those words is an Enterprise Integration Pattern with a Spring Integration building block, so the code in `OrderFlowConfiguration.java` reads like the lesson's diagram.

## Where it appears

| Path | What it does with Spring Integration |
| --- | --- |
| `${F}/OrderFlowConfiguration.java` | The whole flow, in the `flow` profile. |
| `${F}/FileSummary.java` | Reads the file-end marker and reports. |
| `${F}/FlowCounters.java` | The counters the summary and the end-to-end tests read. |
| `src/main/java/plainround/orderflow/infrastructure/sftp/SftpConfiguration.java` | The SFTP session factory (see the SFTP note). |
| `pom.xml` | `spring-boot-starter-integration`, `spring-integration-sftp`, `spring-integration-amqp`, `spring-integration-jdbc`, and `spring-integration-test`. |
| `sql/001_schema.sql` | The table of the metadata store. |

## How it works underneath

A Spring Integration flow is built from ordinary Spring beans. When the application starts, each `IntegrationFlow` bean is expanded into channels (created for you between steps) and endpoint objects, and every endpoint is **subscribed** to its input channel. With a direct channel, sending a message calls the subscriber's method on the sender's own thread: so one poll of the SFTP adapter, and everything downstream of it, runs on one thread (named `scheduling-1` in the log), one message at a time. This is why the counters in `FlowCounters` need no locking beyond atomic numbers.

The **inbound SFTP adapter** is a message source driven by a poller. On each tick the poller calls `receive()`; the adapter lists the remote folder, drops names rejected by its **file list filter**, downloads one new file into a local folder, and returns a message whose payload is the local `File`. `maxMessagesPerPoll(1)` limits one tick to one file. If nothing is new, `receive()` returns nothing and the tick ends.

A **splitter** receiving a file reads it line by line and sends one message per line. With `markers()` it also sends two extra messages: a START marker before the first line and an END marker after the last, carrying the line count. The flow uses them to reset counters and to report.

An **idempotent receiver** is a pattern for "the same message may arrive more than once; process it once". It needs a memory of what was seen. Spring Integration offers a **metadata store**: a map from key to value that can be kept in memory, in a file or in a database. `JdbcMetadataStore` keeps it in the table `INT_METADATA_STORE`, so the memory survives a restart. The `region` separates unrelated maps in one table. `putIfAbsent(key, value)` adds a key only if it is not there and says whether it added it; `get(key)` returns the value or `null`.

A **dead letter channel** is where messages go when they cannot be processed: instead of being dropped or blocking the flow, they are put somewhere a person or a program can look at them later, together with the reason.

**Delivery guarantees.** There is no free "exactly once". The flow therefore gives *at least once* delivery to the broker (a message may be sent again after a crash) and relies on the receiver being idempotent to make the effect *exactly once*. That is why the order of two actions matters: publish, wait for the broker's confirmation, and only then remember the id. If the flow crashes between the two, the order is published again after the restart (harmless, the order service absorbs it); the opposite order could lose an order for good.

## Walkthrough of this repository's use

```java
package plainround.orderflow.infrastructure.flow;

import com.github.sviperll.result4j.Result;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Function;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.sftp.client.SftpClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.Pollers;
import org.springframework.integration.file.dsl.Files;
import org.springframework.integration.file.remote.session.SessionFactory;
import org.springframework.integration.file.splitter.FileSplitter;
import org.springframework.integration.jdbc.metadata.JdbcMetadataStore;
import org.springframework.integration.metadata.ConcurrentMetadataStore;
import org.springframework.integration.sftp.dsl.Sftp;
import org.springframework.integration.sftp.filters.SftpPersistentAcceptOnceFileListFilter;
import org.springframework.util.DigestUtils;
import plainround.orderflow.domain.Order;
import plainround.orderflow.domain.OrderParser;
import plainround.orderflow.domain.Rejection;
import plainround.orderflow.infrastructure.OrderflowProperties;
import plainround.orderflow.infrastructure.amqp.ConfirmedPublisher;

/**
 * The partner's file as a message flow (active in the {@code flow} profile).
 *
 * <pre>
 * SFTP inbound adapter -> Splitter -> (header filter) -> Translator -> Router --invalid--> Dead Letter Channel
 *                                                                          \--valid--> Idempotent Receiver --new--> confirmed publish
 *                                                                                           \--seen--> duplicates
 * </pre>
 */
@Slf4j
@Configuration
@Profile("flow")
public class OrderFlowConfiguration {

```

The imports show the building blocks: `IntegrationFlow` and `Pollers` (the DSL), `Files` (the file DSL, for the splitter), `Sftp` (the SFTP DSL), `SftpPersistentAcceptOnceFileListFilter` (the "fetch each file once" filter), `FileSplitter` (the marker type), `JdbcMetadataStore` and `ConcurrentMetadataStore` (the memory), `RabbitTemplate` (the broker client), and the domain types. The class comment draws the flow: SFTP inbound adapter, Splitter, a header filter, Translator, Router; invalid lines go to the Dead Letter Channel, valid ones to the Idempotent Receiver, which sends new orders to a confirmed publish and seen ones to `duplicates`. `@Configuration` marks the class as a source of beans. `@Profile("flow")` makes it active only when the profile `flow` is on (the Spring Boot note explains profiles); the order service and the command-line tools never poll the folder.

```java
  @Bean
  JdbcMetadataStore orderMemory(DataSource dataSource) {
    JdbcMetadataStore store = new JdbcMetadataStore(dataSource);
    store.setRegion("orders");
    return store;
  }

  @Bean
  JdbcMetadataStore deadLetterMemory(DataSource dataSource) {
    JdbcMetadataStore store = new JdbcMetadataStore(dataSource);
    store.setRegion("dead-letters");
    return store;
  }

  @Bean
  JdbcMetadataStore fileMemory(DataSource dataSource) {
    JdbcMetadataStore store = new JdbcMetadataStore(dataSource);
    store.setRegion("files");
    return store;
  }

  @Bean
  FlowCounters flowCounters() {
    return new FlowCounters();
  }

  @Bean
  ConfirmedPublisher confirmedPublisher(RabbitTemplate template, OrderflowProperties properties) {
    return new ConfirmedPublisher(template, properties.confirmTimeoutMillis());
  }
```

Three `JdbcMetadataStore` beans, one per region: `orders` (order ids already published), `dead-letters` (refused lines already sent to the dead-letter queue) and `files` (SFTP files already fetched). Each is created over the application's `DataSource` and given its region with `setRegion`. `FlowCounters` is the bean that counts what happens to the file in flight. `ConfirmedPublisher` wraps the broker client with the confirmation wait (the RabbitMQ note).

```java
  /**
   * Channel Adapter + Splitter: poll the folder (a file is fetched once, remembered in the
   * database), one message per line.
   */
  @Bean
  IntegrationFlow ingestFlow(
      SessionFactory<SftpClient.DirEntry> sftpSessions,
      ConcurrentMetadataStore fileMemory,
      OrderflowProperties properties) {
    return IntegrationFlow.from(
            Sftp.inboundAdapter(sftpSessions)
                .remoteDirectory(properties.sftp().remoteDirectory())
                .regexFilter(".*\\.csv")
                .filter(new SftpPersistentAcceptOnceFileListFilter(fileMemory, "sftp:"))
                .localDirectory(properties.inbox().toFile())
                .autoCreateLocalDirectory(true),
            spec ->
                spec.poller(
                    Pollers.fixedDelay(Duration.ofSeconds(properties.pollSeconds()))
                        .maxMessagesPerPoll(1)))
        .split(Files.splitter().markers())
        .<Object, Boolean>route(
            payload -> payload instanceof FileSplitter.FileMarker,
            mapping ->
                mapping
                    .subFlowMapping(true, branch -> branch.channel("fileMarkers"))
                    .subFlowMapping(false, branch -> branch.channel("lines")))
        .get();
  }
```

`ingestFlow` is **Channel Adapter + Splitter**. `IntegrationFlow.from(Sftp.inboundAdapter(sftpSessions)...)` starts the flow at an SFTP inbound adapter. `.remoteDirectory(...)` names the folder on the server (`upload`). `.regexFilter(".*\\.csv")` keeps only names ending in `.csv`. `.filter(new SftpPersistentAcceptOnceFileListFilter(fileMemory, "sftp:"))` accepts each file once: it remembers `sftp:<name>` and the file's modification time in the `files` region, so after a restart the same file is not fetched again, and a new file with the same name but a new time would be. `.localDirectory(...)` is where the adapter keeps its local copy (`data/inbox`), and `.autoCreateLocalDirectory(true)` creates it. The second argument of `from` configures the **poller**: `Pollers.fixedDelay(Duration.ofSeconds(properties.pollSeconds()))` waits that long after each tick (5 seconds by default), and `.maxMessagesPerPoll(1)` takes at most one file per tick. Then `.split(Files.splitter().markers())` splits each file into one message per line, with START and END markers around them. `.<Object, Boolean>route(...)` is a **router**: the first function `payload -> payload instanceof FileSplitter.FileMarker` computes a key (`true` for a marker, `false` for a line); the mapping sends `true` to the channel `fileMarkers` and `false` to the channel `lines` through `subFlowMapping`. `.<Object, Boolean>` tells the compiler the input and key types. `.get()` finishes the definition.

```java
  /**
   * Message Filter (drop the header), Message Translator (line to order), Content-Based Router
   * (valid or not).
   */
  @Bean
  IntegrationFlow lineFlow(FlowCounters counters) {
    return IntegrationFlow.from("lines")
        .filter(String.class, line -> !line.startsWith("order_id"))
        .transform(String.class, OrderParser::parse)
        .<Result<Order, Rejection>, Boolean>route(
            Result::isError,
            mapping ->
                mapping
                    .subFlowMapping(true, branch -> branch.channel("invalid"))
                    .subFlowMapping(
                        false,
                        branch ->
                            branch
                                .<Result<Order, Rejection>, Order>transform(this::order)
                                .channel("valid")))
        .get();
  }
```

`lineFlow` starts at the channel `lines` (`IntegrationFlow.from("lines")` names an existing channel). It is **Message Filter**, **Message Translator** and **Content-Based Router**. `.filter(String.class, line -> !line.startsWith("order_id"))` lets a line pass unless it begins with `order_id`, which drops the header; the first argument is the expected payload type. `.transform(String.class, OrderParser::parse)` turns each line into a `Result<Order, Rejection>` using the domain parser. `.route(Result::isError, mapping -> ...)` routes on whether the result is an error: `true` goes to the channel `invalid`; `false` is first transformed with `this::order`, which unwraps the `Order` from the success, and then sent to the channel `valid`. Because the channels are named, three flows can be written separately and joined by name.

```java
  /**
   * Dead Letter Channel: the line and the reason go to a queue, confirmed, instead of being lost. A
   * line that was already dead-lettered, byte for byte, is not sent again (the partner resends the
   * whole file, so the queue would grow with every resend); a corrected line is a different line
   * and is processed as a new one. Like the orders, a line is remembered only after the broker has
   * confirmed it.
   */
  @Bean
  IntegrationFlow invalidFlow(
      ConcurrentMetadataStore deadLetterMemory,
      ConfirmedPublisher publisher,
      FlowCounters counters) {
    return IntegrationFlow.from("invalid")
        .<Result<Order, Rejection>>handle(
            (result, headers) -> {
              Rejection rejection =
                  result.map(order -> (Rejection) null).recoverError(Function.identity());
              deadLetter(
                  rejection,
                  String.valueOf(headers.get("file_name")),
                  deadLetterMemory,
                  publisher,
                  counters);
              return null;
            })
        .get();
  }

  private void deadLetter(
      Rejection rejection,
      String file,
      ConcurrentMetadataStore memory,
      ConfirmedPublisher publisher,
      FlowCounters counters) {
    String key = DigestUtils.md5DigestAsHex(rejection.line().getBytes(StandardCharsets.UTF_8));
    if (memory.get(key) != null) {
      counters.duplicate();
      return;
    }
    publisher.publishDeadLetter(rejection.line(), rejection.reason(), file);
    memory.putIfAbsent(key, "dead-lettered");
    counters.deadLettered();
    log.info("DEAD LETTER: {} reason={}", rejection.line(), rejection.reason());
  }
```

`invalidFlow` is the **Dead Letter Channel**. `.handle((result, headers) -> {...})` is a service activator: the lambda receives the payload and the headers. It extracts the `Rejection` (the Result4J note explains the two calls), reads the header `file_name`, and calls `deadLetter`, then returns `null`: a handler that returns `null` ends the flow for that message (no reply). `deadLetter` computes a key for the line: `DigestUtils.md5DigestAsHex(rejection.line().getBytes(UTF_8))` is the MD5 digest of the line as 32 hexadecimal characters (a short fingerprint of the line's bytes; it is used as an identifier here, not for security). If the memory already holds that key, the line was already dead-lettered, so it is counted as a duplicate and not sent again: the partner resends whole files, and without this the queue would grow with every resend. Otherwise it publishes the line to the dead-letter queue with the reason and the file name (confirmed), **then** remembers the key with `putIfAbsent`, counts it and logs `DEAD LETTER: ... reason=...`. A corrected line has different bytes, so it is a different key and is processed as new. Like the orders, a line is remembered only after the broker has confirmed it.

```java
  /**
   * Idempotent Receiver, then a confirmed publish, then the memory. The id is remembered only AFTER
   * the broker has confirmed the order: a crash between the two publishes the order again on
   * restart (the order service absorbs it), where remembering first would lose it for good. The
   * check is a plain lookup, not {@code MetadataStoreSelector}'s put-then-pass.
   */
  @Bean
  IntegrationFlow validFlow(
      ConcurrentMetadataStore orderMemory, ConfirmedPublisher publisher, FlowCounters counters) {
    return IntegrationFlow.from("valid")
        .filter(
            Order.class,
            order -> orderMemory.get(order.orderId()) == null,
            spec -> spec.discardChannel("duplicates"))
        .<Order>handle(
            (order, headers) -> {
              publisher.publishOrder(order, String.valueOf(headers.get("file_name")));
              orderMemory.putIfAbsent(order.orderId(), "published");
              counters.published();
              return null;
            })
        .get();
  }
```

`validFlow` is the **Idempotent Receiver** followed by the confirmed publish. `.filter(Order.class, order -> orderMemory.get(order.orderId()) == null, spec -> spec.discardChannel("duplicates"))` lets an order through when its id is not in the `orders` memory; an order that is filtered out is sent to the **discard channel** `duplicates` instead of vanishing. The comment explains the deliberate choice of a plain lookup (`get`) instead of Spring Integration's `MetadataStoreSelector`, which writes the key before the message passes (put-then-pass): that would remember an order before the broker has confirmed it. `.handle(...)` publishes the order to the queue of its type with `publisher.publishOrder(order, fileName)`, which returns only after the broker's confirmation (or throws), and **after that** calls `orderMemory.putIfAbsent(order.orderId(), "published")`, counts it, and returns `null`. If the process dies between the publish and the `putIfAbsent`, the order is published again after the restart; the order service's primary key absorbs the second copy.

```java
  @Bean
  IntegrationFlow duplicatesFlow(FlowCounters counters) {
    return IntegrationFlow.from("duplicates").handle(message -> counters.duplicate()).get();
  }

  /** The markers around a file: reset the counters at the start, report and clean up at the end. */
  @Bean
  IntegrationFlow markerFlow(FlowCounters counters) {
    return IntegrationFlow.from("fileMarkers")
        .<FileSplitter.FileMarker>handle(
            (marker, headers) -> {
              new FileSummary(counters).on(marker);
              return null;
            })
        .get();
  }

  private Order order(Result<Order, Rejection> result) {
    return result.map(Function.identity()).discardError().orElseThrow();
  }
}
```

`duplicatesFlow` listens on the channel `duplicates` and only counts each duplicate. `markerFlow` listens on `fileMarkers` and hands each marker to `FileSummary`. `order(...)` is the unwrapping used by `lineFlow`.

```java
package plainround.orderflow.infrastructure.flow;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.integration.file.splitter.FileSplitter;

/**
 * Reports what the flow did with a file when its end marker arrives, then removes the local copy.
 */
@Slf4j
@RequiredArgsConstructor
class FileSummary {

  private final FlowCounters counters;

  void on(FileSplitter.FileMarker marker) {
    if (marker.getMark() == FileSplitter.FileMarker.Mark.START) {
      counters.reset();
    } else {
      report(marker);
    }
  }

  private void report(FileSplitter.FileMarker marker) {
    log.info(
        "flow: split {} lines -> {} orders (header dropped)",
        marker.getLineCount(),
        marker.getLineCount() - 1);
    log.info("flow: {} to dead letter", counters.deadLetteredCount());
    log.info("flow: {} duplicate(s) dropped", counters.duplicateCount());
    log.info("flow: {} published", counters.publishedCount());
    delete(marker.getFilePath());
  }

  private void delete(String path) {
    try {
      Files.deleteIfExists(java.nio.file.Path.of(path));
    } catch (IOException problem) {
      throw new UncheckedIOException(problem);
    }
  }
}
```

`FileSummary` is created per marker. For the START marker it resets the counters. For the END marker it logs the four summary lines (lines split, dead-lettered, duplicates dropped, published) and deletes the local copy of the file (`Files.deleteIfExists`); the lines-minus-one in the first message is the header. `marker.getLineCount()` is the number of lines the splitter saw; `getFilePath()` is the local copy. An `IOException` becomes an unchecked exception.

```java
package plainround.orderflow.infrastructure.flow;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * What the flow did with the file in flight. The flow is single-threaded per file, so plain
 * counters are enough.
 */
public class FlowCounters {

  private final AtomicInteger valid = new AtomicInteger();
  private final AtomicInteger deadLettered = new AtomicInteger();
  private final AtomicInteger duplicates = new AtomicInteger();
  private final AtomicInteger published = new AtomicInteger();

  /** Starts counting a new file. */
  public void reset() {
    valid.set(0);
    deadLettered.set(0);
    duplicates.set(0);
    published.set(0);
  }

  public void valid() {
    valid.incrementAndGet();
  }

  public void deadLettered() {
    deadLettered.incrementAndGet();
  }

  public void duplicate() {
    duplicates.incrementAndGet();
  }

  public void published() {
    published.incrementAndGet();
  }

```

`FlowCounters` holds four `AtomicInteger`s (numbers that can be changed safely from several threads) and methods to change them; `reset` sets all to zero at the start of a file. The Javadoc explains why plain counters are enough: the flow is single-threaded per file.

```java
  public int validCount() {
    return valid.get();
  }

  public int deadLetteredCount() {
    return deadLettered.get();
  }

  public int duplicateCount() {
    return duplicates.get();
  }

  public int publishedCount() {
    return published.get();
  }
}
```

The read side: `validCount`, `deadLetteredCount`, `duplicateCount`, `publishedCount`. The end-to-end tests read these to know when a file has been fully handled (`duplicateCount() == 9997 + 3 && publishedCount() == 0` for a resent file).

## Try it yourself

1. Start the services and the flow: `make up db apps`.
2. Drop a file and watch the flow: `make partner`, then `make logs`. Expect lines `flow: split 10002 lines -> 10001 orders (header dropped)`, `flow: 3 to dead letter`, `flow: 1 duplicate(s) dropped` and `flow: 9997 published`.
3. See the idempotent receiver: `make partner-again`, wait 20 seconds, `make logs`. Expect `flow: 10000 duplicate(s) dropped` (9,997 orders and the 3 bad lines) and `flow: 0 published`.
4. See the memory: `make queries` and read the second result: regions `dead-letters`, `files`, `orders` with counts 3, 2 and 9,997.
5. Read the unit and end-to-end tests that exercise the flow: `src/test/java/plainround/orderflow/e2e/ReplaceFileDropE2E.java`; run them with `make test-e2e`.

## Mistakes people make

- **Remembering before the broker has confirmed.** A crash then loses an order for ever. Publish, confirm, remember, in that order.
- **A handler that returns a value by accident.** In a flow that ends there, return `null`, otherwise Spring Integration looks for a reply channel and fails.
- **Putting slow work on the poller thread without noticing.** With direct channels everything runs on the poller thread; a slow step delays the next poll.
- **Forgetting that the same message can arrive twice** (a retry, a restart, a resent file). Every consumer that has an effect must be idempotent.
- **Using the file name as the identity of a file.** The partner may reuse a name; the filter used here combines the name and the modification time.

## Check yourself

1. What is a message in Spring Integration? *A payload with headers, immutable.*
2. What does a router do? *Sends each message to one of several channels, depending on its content.*
3. Why does the flow remember an order id only after the broker's confirmation? *If it crashed in between, the order would be lost for good; remembering afterwards at worst publishes it twice.*
4. What does the discard channel of a filter carry? *The messages the filter rejected.*
5. Why is `maxMessagesPerPoll(1)` set? *One file per tick keeps a tick short and the counters per file correct.*

## Further reading

- Spring Integration reference: https://docs.spring.io/spring-integration/reference/6.5/index.html
- Java DSL: https://docs.spring.io/spring-integration/reference/6.5/dsl.html
- SFTP adapters: https://docs.spring.io/spring-integration/reference/6.5/sftp.html
- Metadata store: https://docs.spring.io/spring-integration/reference/6.5/meta-data-store.html
- Enterprise Integration Patterns: https://www.enterpriseintegrationpatterns.com/patterns/messaging/
