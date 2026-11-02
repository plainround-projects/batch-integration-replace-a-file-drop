# Awaitility

## What it is

Awaitility is a small Java library for tests that have to **wait** for something that happens in the background: a message arriving, a row appearing in a database, a thread finishing. Instead of `Thread.sleep(5000)` ("wait five seconds and hope"), you write `await().atMost(Duration.ofMinutes(1)).until(() -> condition)`; Awaitility checks the condition again and again (by default every 100 milliseconds) and continues the moment it is true, or fails with a clear message if the time runs out.

## Why this repository uses it

The project rule for tests is "Awaitility for asynchronous flows", and here it is needed. The flow polls the SFTP folder on its own thread, the broker delivers messages to the order service on other threads, and the tests can only ask "has it happened yet?". A fixed `Thread.sleep` would make the tests either slow or flaky.

## Where it appears

| Path | What it does with Awaitility |
| --- | --- |
| `pom.xml` | Declares the dependency with `<scope>test</scope>`. |
| `src/test/java/plainround/orderflow/integration/StoresAndBrokerIT.java` | Waits for the rejected message and for the stored order. |
| `src/test/java/plainround/orderflow/e2e/ReplaceFileDropE2E.java` | Waits for queue depths, counters and table counts in every step of the scenario. |

## How it works underneath

Awaitility polls: it runs your condition (a lambda that returns `true` or `false`, or an assertion that throws until it holds) on a schedule, with an initial delay, a poll interval and a deadline. Polling happens on a separate thread by default, so a slow condition does not block the clock. When the condition is true the call returns; when the deadline passes it throws `ConditionTimeoutException` with a description of what was awaited.

## Walkthrough of this repository's use

The dependency declaration:

```xml
    <dependency>
      <groupId>org.awaitility</groupId>
      <artifactId>awaitility</artifactId>
      <scope>test</scope>
    </dependency>
```

`<groupId>org.awaitility</groupId>` and `<artifactId>awaitility</artifactId>` are the coordinates; there is no `<version>` because the Spring Boot parent manages it; `<scope>test</scope>` keeps it out of the shipped program.

A wait in an integration test:

```java
      Awaitility.await()
          .atMost(Duration.ofSeconds(60))
          .until(() -> TestStack.depth(admin, "rejected") == 1);
      new ConfirmedPublisher(template, 5000).publishOrder(order("O9", "5.00"), "x.csv");
      Awaitility.await()
          .atMost(Duration.ofSeconds(60))
          .until(() -> jdbc.queryForObject("SELECT count(*) FROM orders_events", Long.class) == 1);
```

`Awaitility.await().atMost(Duration.ofSeconds(60)).until(() -> TestStack.depth(admin, "rejected") == 1)` checks the condition about every 100 milliseconds (the default) and continues the moment it is true; if 60 seconds pass it throws. The second wait polls the table: `jdbc.queryForObject("SELECT count(*) FROM orders_events", Long.class) == 1`.

Waits in the end-to-end scenario:

```java
  @Test
  @Order(1)
  void a_file_becomes_9997_messages_and_the_3_bad_lines_wait_in_the_dead_letter_queue()
      throws Exception {
    try (ConfigurableApplicationContext flow = TestStack.start(inbox, "flow")) {
      RabbitAdmin admin = flow.getBean(RabbitAdmin.class);
      upload(flow, 1, "orders-1.csv");
      Awaitility.await()
          .atMost(LONG)
          .pollInterval(Duration.ofSeconds(2))
          .until(() -> TestStack.depth(admin, "dead-letter") == 3);
      Awaitility.await().atMost(LONG).until(() -> published(admin) == 9997);
      assertThat(TestStack.depth(admin, "retail") + TestStack.depth(admin, "wholesale"))
          .isEqualTo(9997);
    }
  }
```

`LONG` is a constant of six minutes (`Duration.ofMinutes(6)`), a generous limit that a calm machine never reaches. `.pollInterval(Duration.ofSeconds(2))` checks every two seconds instead of every 100 ms, because each check here is a call to the broker. The two waits run one after the other: first the dead-letter queue must hold 3 messages, then the order queues together 9,997.

```java
  @Test
  @Order(6)
  void
      a_flow_stopped_in_the_middle_of_a_file_and_started_again_loses_no_order_and_creates_no_duplicate_row()
          throws Exception {
    TestStack.resetDatabase();
    Path second = folder.resolve("second");
    try (ConfigurableApplicationContext tools = TestStack.start(second)) {
      tools.getBean(SftpPartnerDrop.class).clear();
      TestStack.purgeQueues(tools.getBean(RabbitAdmin.class));
    }
    try (ConfigurableApplicationContext flow = TestStack.start(second, "flow")) {
      RabbitAdmin admin = flow.getBean(RabbitAdmin.class);
      upload(flow, 2, "orders-2.csv");
      Awaitility.await()
          .atMost(LONG)
          .pollInterval(Duration.ofMillis(200))
          .until(() -> published(admin) > 1500);
    }
```

In the last scenario the poll interval is 200 ms: the test must catch the flow while it is still publishing (more than 1,500 orders, fewer than all) and stop it, so a fast check matters.

## Try it yourself

1. Find the declaration: `grep -n -B1 -A3 awaitility pom.xml`.
2. See that Spring Boot manages the version: `./mvnw -q dependency:tree -Dincludes=org.awaitility` prints `org.awaitility:awaitility:jar:...:test`.
3. Find the uses: `grep -rn "await()" src/test | wc -l`.
4. Write a throwaway test that waits for a background thread to set a flag: `await().atMost(Duration.ofSeconds(2)).untilTrue(flag)`; delete it afterwards.
5. Make that test fail on purpose by never setting the flag; the failure message says the condition was not fulfilled within 2 seconds.

## Mistakes people make

- **`Thread.sleep` in tests.** It is either too short (a flaky test) or too long (a slow suite). Poll for the condition.
- **Waiting for something that is already synchronous.** If the call returns when the work is done, assert directly.
- **A very long timeout hiding a hang.** Keep `atMost` close to the real worst case.

## Check yourself

1. What does Awaitility do instead of sleeping? *It checks a condition repeatedly until it is true or a deadline passes.*
2. What does it throw on timeout? *`ConditionTimeoutException`.*
3. Why do the end-to-end tests use it? *The flow and the order service work on their own threads; the test can only ask whether the effect has appeared yet.*
4. Why is there no `<version>` in the pom? *Spring Boot's parent manages it.*
5. What does `<scope>test</scope>` mean? *The library is on the test classpath only.*

## Further reading

- Awaitility usage guide: https://github.com/awaitility/awaitility/wiki/Usage
- Awaitility on Maven Central (version managed by Spring Boot 3.5.x): https://central.sonatype.com/artifact/org.awaitility/awaitility
