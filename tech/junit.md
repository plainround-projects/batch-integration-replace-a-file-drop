# JUnit 5 (and the test files)

## What it is

A **test** is a small program that checks that another program behaves as promised. **JUnit 5** (its modern part is called **Jupiter**) is the standard Java tool for writing and running tests. A test is an ordinary method with the annotation `@Test`; JUnit finds every such method, runs each one, and reports which passed and which failed. A test **fails** when it throws an exception, which is what a failed assertion does.

Words you will meet: a **unit test** checks one small piece (a single class) with no database or network; an **integration test** checks several real parts together (here the job and a real PostgreSQL); an **end-to-end test** (e2e) checks a whole scenario the way a user would run it. A **fixture** is the data and setup a test needs. A **tag** (`@Tag("integration")`) is a label used to include or exclude groups of tests.

## Why this repository uses it

The lesson's promises are claims about behavior: a bad line goes to the dead-letter queue with its reason, a resent file adds nothing, a restart of the broker or the service loses and doubles nothing, and the old and new paths reconcile. Each claim is a test. Unit tests guard the pure rules and the use cases with fakes; integration tests run the adapters against the real database and the real broker; one end-to-end test runs the lesson's scenario in order against the real SFTP server, broker and database.

## Where it appears

| Path | What it tests |
| --- | --- |
| `${T}/domain/DomainTest.java` | Parsing a line, the order type, the report and the retirement policy. |
| `${T}/application/ApplicationTest.java` | The order service, the reconciliation job and the file generator, with fakes. |
| `${T}/architecture/ArchitectureTest.java` | The dependency rule (ArchUnit note). |
| `${T}/integration/TestStack.java` | A test database and application contexts for the other tests. |
| `${T}/integration/StoresAndBrokerIT.java` | The adapters against PostgreSQL and RabbitMQ. |
| `${T}/e2e/ReplaceFileDropE2E.java` | The lesson's scenario, six tests in order. |

## How it works underneath

Maven runs tests through two plug-ins. **Surefire** runs unit tests during `mvn test`; its configuration here excludes the tags `integration` and `e2e`, so unit tests need no services. **Failsafe** runs the integration and end-to-end tests during `failsafe:integration-test` and `failsafe:verify`; it only picks up classes whose names end in `IT` or `E2E` (the `includes` in `pom.xml`) and only the tags named by the property `failsafe.groups`.

For each test class JUnit creates a new instance, so tests cannot leak state through instance fields. Lifecycle annotations run code around the tests: `@BeforeAll` once before all tests of a class (the method must be `static`), `@BeforeEach` before every test, `@AfterAll` and `@AfterEach` the same after. `@TempDir` gives the test a fresh empty folder that JUnit deletes afterwards. `@ParameterizedTest` runs one test method many times with different inputs from `@CsvSource` (rows of comma-separated values) or `@ValueSource` (a list of values). `@TestMethodOrder` with `@Order(n)` forces the order of test methods, used when one test builds on the state left by the previous one (the end-to-end scenario).

Two more pieces appear here. **`@SpringBootApplication` started by hand**: `TestStack.start` uses `SpringApplicationBuilder` to start the real application inside the test's JVM, with the profile of the part under test (`flow`, `order-service` or neither), and closes it at the end of a `try (...)` block (a Spring context is `AutoCloseable`). And **ordered tests**: `@TestMethodOrder(MethodOrderer.OrderAnnotation.class)` with `@Order(n)` runs the methods of one class in a fixed sequence, which an end-to-end scenario needs because each step builds on the previous one.

## Walkthrough of this repository's use

### Surefire and Failsafe in `pom.xml`

```xml
      <!-- Unit tests run on every build; tests tagged integration or e2e need the services of docker-compose.yml. -->
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <configuration>
          <excludedGroups>integration,e2e</excludedGroups>
          <argLine>@{argLine} -XX:+EnableDynamicAgentLoading</argLine>
        </configuration>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-failsafe-plugin</artifactId>
        <configuration>
          <includes>
            <include>**/*IT.java</include>
            <include>**/*E2E.java</include>
          </includes>
          <groups>${failsafe.groups}</groups>
          <argLine>@{argLine} -XX:+EnableDynamicAgentLoading</argLine>
        </configuration>
        <executions>
          <execution>
            <goals>
              <goal>integration-test</goal>
              <goal>verify</goal>
            </goals>
          </execution>
        </executions>
      </plugin>
```

The comment says it plainly: unit tests run on every build, tests tagged `integration` or `e2e` need the services of the compose file. `maven-surefire-plugin` runs the unit tests; `<excludedGroups>integration,e2e</excludedGroups>` leaves out every test with one of those tags. `<argLine>@{argLine} -XX:+EnableDynamicAgentLoading</argLine>` is the option string for the test JVM: `@{argLine}` is what JaCoCo added (the coverage agent), and the flag lets an agent attach on newer JDKs without a warning. `maven-failsafe-plugin` runs the slower tests: `<includes>` names the classes it may pick up, those ending in `IT` or `E2E` (without this, Failsafe's default patterns miss the `E2E` class and it would never run; that was a real bug in this repository), `<groups>${failsafe.groups}</groups>` runs only the tags in the property, and the two goals are `integration-test` (run) and `verify` (fail the build if any failed).

### `DomainTest.java`: the rules

```java
package plainround.orderflow.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.sviperll.result4j.Result;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

class DomainTest {

  private String reason(String line) {
    return OrderParser.parse(line).map(order -> "valid").recoverError(Rejection::reason);
  }

  @Test
  void a_good_line_becomes_an_order() {
    Result<Order, Rejection> parsed = OrderParser.parse("O000010,C0042,retail,123.45");
    assertThat(parsed.discardError())
        .contains(new Order("O000010", "C0042", OrderType.RETAIL, new BigDecimal("123.45")));
  }

  @Test
  void the_type_is_case_insensitive_and_whitespace_is_ignored() {
    assertThat(OrderParser.parse(" O1 , C1 , Wholesale , 10 ").discardError())
        .get()
        .extracting(Order::type)
        .isEqualTo(OrderType.WHOLESALE);
  }
```

Lines 3 and 4 are **static imports** so the test can write `assertThat(...)` and `assertThatThrownBy(...)` without the class name. `reason(line)` parses a line and turns the outcome into the reason text or `"valid"`: `OrderParser.parse(line).map(order -> "valid").recoverError(Rejection::reason)` (a success becomes the text `valid`; an error becomes its reason; after `recoverError` both are `String`). The first test parses `O000010,C0042,retail,123.45` and asserts that the result holds exactly the expected `Order`: `discardError()` turns the success into an `Optional`, and `.contains(...)` checks the value inside. The second test shows that the type is case-insensitive and that spaces around fields are ignored (`" O1 , C1 , Wholesale , 10 "`): `.get().extracting(Order::type).isEqualTo(OrderType.WHOLESALE)` unwraps the optional and checks one property.

```java
  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "O999001,C0001,retail,|amount missing",
        "O999002,C0002,retail,-4|amount must be above zero",
        "O999004,C0002,retail,0|amount must be above zero",
        "O999003,C0003,barter,10|unknown order type barter",
        ",C1,retail,10|order id missing",
        "O1,,retail,10|customer missing",
        "O1,C1,retail,abc|amount is not a number with at most two decimals",
        "O1,C1,retail,10.123|amount is not a number with at most two decimals",
        "O1,C1,retail|malformed line: expected 4 fields",
        "O1,C1,retail,10,extra|malformed line: expected 4 fields"
      })
  void a_bad_line_is_refused_with_the_reason_the_dead_letter_queue_will_carry(
      String line, String expected) {
    assertThat(reason(line)).isEqualTo(expected);
  }

  @Test
  void the_rejection_keeps_the_original_line() {
    Rejection rejection =
        OrderParser.parse("O1,C1,barter,10").map(order -> (Rejection) null).recoverError(r -> r);
    assertThat(rejection.line()).isEqualTo("O1,C1,barter,10");
  }
```

`@ParameterizedTest` with `@CsvSource(delimiter = '|', value = {...})` runs one test body ten times, once per row. Each row is `line|expected reason`; the delimiter is a bar because the lines themselves contain commas. The rows are the lesson's three planted bad lines (`amount missing`, `amount must be above zero` for -4 and for 0, `unknown order type barter`) and the other rules (`order id missing`, `customer missing`, `amount is not a number with at most two decimals` for `abc` and for `10.123`, and `malformed line: expected 4 fields` for three and for five fields). The last test checks that the rejection keeps the original line, which the dead-letter queue publishes.

```java
  @ParameterizedTest
  @NullAndEmptySource
  @CsvSource({"barter", "RETAILS"})
  void an_unknown_type_is_empty(String field) {
    assertThat(OrderType.parse(field)).isEmpty();
  }

  @Test
  void the_queue_of_a_type_is_its_lower_case_name() {
    assertThat(OrderType.RETAIL.queue()).isEqualTo("retail");
    assertThat(OrderType.WHOLESALE.queue()).isEqualTo("wholesale");
  }

  @Test
  void a_report_is_clean_only_when_nothing_differs_on_either_side() {
    assertThat(new ReconciliationReport(0, 0, 0, 9996).isClean()).isTrue();
    assertThat(new ReconciliationReport(1, 0, 0, 9996).isClean()).isFalse();
    assertThat(new ReconciliationReport(0, 1, 0, 9996).isClean()).isFalse();
    assertThat(new ReconciliationReport(0, 0, 1, 9996).isClean()).isFalse();
  }
```

`@NullAndEmptySource` feeds `null` and `""` to the test, and `@CsvSource({"barter", "RETAILS"})` adds two more unknown types: all four must give an empty `Optional`. The next test pins the queue names. The test of `ReconciliationReport.isClean` checks the four cases: clean only when nothing is on one side only and nothing differs.

```java
  private ReconciliationReport clean() {
    return new ReconciliationReport(0, 0, 0, 100);
  }

  @Test
  void the_legacy_path_may_retire_only_after_enough_clean_nights_in_a_row() {
    RetirementPolicy policy = new RetirementPolicy(3);
    assertThat(policy.canRetire(List.of(clean(), clean()))).isFalse();
    assertThat(policy.canRetire(List.of(clean(), clean(), clean()))).isTrue();
    assertThat(
            policy.canRetire(
                List.of(clean(), new ReconciliationReport(0, 0, 1, 99), clean(), clean())))
        .isFalse();
    assertThat(
            policy.canRetire(
                List.of(clean(), clean(), clean(), new ReconciliationReport(5, 0, 0, 1))))
        .isTrue();
  }

  @Test
  void a_policy_needs_at_least_one_night() {
    assertThatThrownBy(() -> new RetirementPolicy(0)).hasMessageContaining("at least 1");
  }
}
```

`clean()` is a helper that builds a clean report. The retirement test runs the rule with `RetirementPolicy(3)`: two clean nights are not enough; three are; a dirty night among the newest three resets the count; and older dirty reports do not matter once the newest three are clean. The last test asserts that a policy of 0 nights throws with a message containing `at least 1` (`hasMessageContaining`).

### `ApplicationTest.java`: use cases with fakes

```java
class ApplicationTest {

  @TempDir Path folder;

  private final Set<String> stored = new HashSet<>();
  private final Order order = new Order("O1", "C1", OrderType.RETAIL, new BigDecimal("10.00"));

  @Test
  void the_first_delivery_is_stored_and_a_repeated_one_is_a_duplicate_that_changes_nothing() {
    ProcessOrderMessage process = new ProcessOrderMessage(o -> stored.add(o.orderId()));
    assertThat(process.handle(order)).isEqualTo(Outcome.STORED);
    assertThat(process.handle(order)).isEqualTo(Outcome.DUPLICATE);
    assertThat(stored).containsExactly("O1");
  }
```

`@TempDir Path folder` is a fresh empty folder that JUnit deletes afterwards. The first test needs no database: `new ProcessOrderMessage(o -> stored.add(o.orderId()))` passes a lambda as the `OrderStore` port (the interface has one method, so a lambda can implement it); `Set.add` returns `false` for a repeated element, which behaves like the table's primary key. The first delivery gives `STORED`, the second `DUPLICATE`, and the set holds `O1` once.

```java
  private static class Fakes implements ReconciliationSource, ReconciliationHistory {
    ReconciliationReport next = new ReconciliationReport(0, 0, 0, 100);
    final List<ReconciliationReport> recorded = new ArrayList<>();

    @Override
    public ReconciliationReport compare() {
      return next;
    }

    @Override
    public List<String> differences(int limit) {
      return List.of("O7 different (batch 10.00/RECEIVED, events 11.00/RECEIVED)");
    }

    @Override
    public void record(ReconciliationReport report) {
      recorded.add(0, report);
    }

    @Override
    public List<ReconciliationReport> latest(int count) {
      return recorded.stream().limit(count).toList();
    }
  }

  @Test
  void a_clean_night_is_recorded_exits_zero_and_lists_no_differences() {
    Fakes fakes = new Fakes();
    ReconcileOrders.Outcome outcome =
        new ReconcileOrders(fakes, fakes, new RetirementPolicy(2)).run();
    assertThat(outcome.exitCode()).isZero();
    assertThat(outcome.differences()).isEmpty();
    assertThat(outcome.mayRetireLegacy()).isFalse();
    assertThat(fakes.recorded).hasSize(1);
  }
```

`Fakes` is a hand-written test double that implements both reconciliation ports: `next` is the report to return, `recorded` is the history (newest first, `recorded.add(0, report)`), and `differences` returns one fixed line. The test of a clean night checks the exit code is zero, no differences are listed, retirement is not yet allowed (the policy needs two nights) and one report was recorded.

```java
  @Test
  void
      enough_clean_nights_in_a_row_allow_retirement_and_a_dirty_night_lists_the_orders_and_exits_one() {
    Fakes fakes = new Fakes();
    ReconcileOrders reconcile = new ReconcileOrders(fakes, fakes, new RetirementPolicy(2));
    reconcile.run();
    assertThat(reconcile.run().mayRetireLegacy()).isTrue();
    fakes.next = new ReconciliationReport(0, 0, 1, 99);
    ReconcileOrders.Outcome dirty = reconcile.run();
    assertThat(dirty.exitCode()).isEqualTo(1);
    assertThat(dirty.differences()).hasSize(1);
    assertThat(dirty.mayRetireLegacy()).isFalse();
  }
```

The long test name states the behavior. Two clean runs allow retirement with a policy of 2; then the fake is set to a report with one difference: the third run exits with 1, lists one difference and no longer allows retirement.

```java
  @Test
  void the_partner_file_has_the_lessons_shape() throws IOException {
    Path file = folder.resolve("orders-1.csv");
    int valid = new GeneratePartnerFile().write(file, 1, 1);
    List<String> lines = Files.readAllLines(file);
    assertThat(valid).isEqualTo(9997);
    assertThat(lines).hasSize(10_002);
    assertThat(lines.getFirst()).isEqualTo("order_id,customer,type,amount");
    assertThat(lines.getLast()).isEqualTo(lines.get(11));
    long bad = lines.stream().skip(1).filter(line -> OrderParser.parse(line).isError()).count();
    assertThat(bad).isEqualTo(3);
    assertThat(
            lines.stream()
                .skip(1)
                .distinct()
                .filter(line -> !OrderParser.parse(line).isError())
                .count())
        .isEqualTo(9997);
  }

  @Test
  void different_nights_use_different_order_ids() throws IOException {
    new GeneratePartnerFile().write(folder.resolve("a.csv"), 1, 1);
    new GeneratePartnerFile().write(folder.resolve("b.csv"), 2, 2);
    Set<String> first =
        Files.readAllLines(folder.resolve("a.csv")).stream()
            .skip(1)
            .map(l -> l.split(",")[0])
            .collect(java.util.stream.Collectors.toSet());
    Set<String> second =
        Files.readAllLines(folder.resolve("b.csv")).stream()
            .skip(1)
            .map(l -> l.split(",")[0])
            .collect(java.util.stream.Collectors.toSet());
    assertThat(first).doesNotContainAnyElementsOf(second);
  }
```

The generator tests read the file back (`Files.readAllLines`): the generator reports 9,997 valid orders; the file has 10,002 lines; the first is the header; the last equals line 11 (the repeated one); exactly 3 lines are refused by the parser; and 9,997 distinct lines are accepted. The last test generates two files for different nights and asserts with `doesNotContainAnyElementsOf` that the order ids of the two sets share nothing.

### `TestStack.java`: shared helpers

```java
public final class TestStack {

  private static final String DB_HOST = System.getenv().getOrDefault("DB_HOST", "localhost");
  private static final String DB_PORT = System.getenv().getOrDefault("POSTGRES_PORT", "55561");
  private static final String DB_USER = System.getenv().getOrDefault("DB_USER", "orderflow");
  private static final String DB_PASSWORD = System.getenv().getOrDefault("DB_PASSWORD", "");
  private static final String NAME = "orderflow_test";
  private static boolean ready;

  private TestStack() {}

  public static synchronized String dbUrl() {
    if (!ready) {
      createDatabase();
      ready = true;
    }
    return "jdbc:postgresql://%s:%s/%s".formatted(DB_HOST, DB_PORT, NAME);
  }

  public static JdbcTemplate jdbc() {
    return new JdbcTemplate(new DriverManagerDataSource(dbUrl(), DB_USER, DB_PASSWORD));
  }

  /** Forgets every order on both paths and everything the receiver remembers. */
  public static void resetDatabase() {
    jdbc().execute("TRUNCATE orders_batch, orders_events, legacy_loaded_file, reconciliation_run");
    jdbc().execute("DELETE FROM int_metadata_store");
  }
```

A final class with a private constructor, only static helpers. It reads connection details from environment variables with defaults, creates its own database (`orderflow_test`) once per JVM, and gives out a `JdbcTemplate`. `resetDatabase` empties both order tables, the loaded-file record, the reconciliation history and the receiver's memory.

```java
  /**
   * Starts an application context with the given profiles (for example {@code flow}, {@code
   * order-service}).
   */
  public static ConfigurableApplicationContext start(Path inbox, String... profiles) {
    // Command-line arguments outrank application.yml; default properties would not.
    return new SpringApplicationBuilder(OrderFlowApplication.class)
        .profiles(profiles)
        .run(
            "--command=serve",
            "--spring.datasource.url=" + dbUrl(),
            "--spring.datasource.username=" + DB_USER,
            "--spring.datasource.password=" + DB_PASSWORD,
            "--orderflow.poll-seconds=1",
            "--orderflow.inbox=" + inbox);
  }

  /** Messages waiting in a queue. */
  public static int depth(RabbitAdmin admin, String queue) {
    QueueInformation info = admin.getQueueInfo(queue);
    return info == null ? 0 : info.getMessageCount();
  }

  /** Empties the four queues of the project. */
  public static void purgeQueues(RabbitAdmin admin) {
    Stream.of("retail", "wholesale", "dead-letter", "rejected").forEach(admin::purgeQueue);
  }
```

`start` builds an application context with the given profiles (`SpringApplicationBuilder(...).profiles(profiles).run(args)`). The comment gives a lesson learned: settings given as command-line arguments (`--spring.datasource.url=...`) outrank `application.yml`, while "default properties" do not, so the test passes the database address, a 1-second poll and the inbox folder as arguments. `depth` asks a queue for its message count; `purgeQueues` empties the four queues with `RabbitAdmin`.

### `StoresAndBrokerIT.java`: the adapters, for real

```java
/** The adapters against the real database and the real broker. */
@Tag("integration")
class StoresAndBrokerIT {

  @TempDir Path inbox;
  private JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    TestStack.resetDatabase();
    jdbc = TestStack.jdbc();
  }

  private Order order(String id, String amount) {
    return new Order(id, "C1", OrderType.RETAIL, new BigDecimal(amount));
  }

  @Test
  void the_primary_key_makes_a_second_delivery_of_an_order_a_no_op() {
    JdbcOrderStore store = new JdbcOrderStore(jdbc);
    assertThat(store.saveIfAbsent(order("O1", "10.00"))).isTrue();
    assertThat(store.saveIfAbsent(order("O1", "99.00"))).isFalse();
    assertThat(
            jdbc.queryForObject(
                "SELECT amount FROM orders_events WHERE order_id = 'O1'", BigDecimal.class))
        .isEqualByComparingTo("10.00");
  }
```

`@Tag("integration")` is what Surefire excludes and Failsafe selects. `@BeforeEach` runs before every test: it resets the database. The first test inserts the same order twice through the real `JdbcOrderStore`: the first call returns `true`, the second `false`, and the stored amount is still 10.00, not 99.00 (`isEqualByComparingTo` compares decimals by value).

```java
  @Test
  void the_reconciliation_counts_each_case_in_one_pass_and_names_the_orders() {
    jdbc.update(
        "INSERT INTO orders_batch (order_id, customer, type, amount) VALUES ('A','C','retail',1),('B','C','retail',2),('C','C','retail',3),('D','C','retail',4)");
    jdbc.update(
        "INSERT INTO orders_events (order_id, customer, type, amount) VALUES ('B','C','retail',2),('C','C','retail',33),('D','C','retail',4),('E','C','retail',5)");
    JdbcReconciliation reconciliation = new JdbcReconciliation(jdbc);
    assertThat(reconciliation.compare()).isEqualTo(new ReconciliationReport(1, 1, 1, 2));
    assertThat(reconciliation.differences(10))
        .hasSize(3)
        .anyMatch(line -> line.startsWith("A only in batch"))
        .anyMatch(line -> line.startsWith("C different"))
        .anyMatch(line -> line.startsWith("E only in events"));
  }

  @Test
  void a_status_difference_counts_as_different_too() {
    jdbc.update(
        "INSERT INTO orders_batch (order_id, customer, type, amount, status) VALUES ('A','C','retail',1,'RECEIVED')");
    jdbc.update(
        "INSERT INTO orders_events (order_id, customer, type, amount, status) VALUES ('A','C','retail',1,'SHIPPED')");
    assertThat(new JdbcReconciliation(jdbc).compare().different()).isEqualTo(1);
  }

  @Test
  void the_history_keeps_the_latest_reports_newest_first() {
    JdbcReconciliation reconciliation = new JdbcReconciliation(jdbc);
    reconciliation.record(new ReconciliationReport(0, 0, 0, 10));
    reconciliation.record(new ReconciliationReport(0, 0, 1, 9));
    assertThat(reconciliation.latest(5))
        .containsExactly(
            new ReconciliationReport(0, 0, 1, 9), new ReconciliationReport(0, 0, 0, 10));
  }
```

The reconciliation test builds four orders on one side and four on the other so that each case occurs once: A only in batch, E only in events, C with different amounts, B and D the same. It asserts the counts `ReconciliationReport(1, 1, 1, 2)` and that the three difference lines are the expected ones. A second test shows that a different status alone counts as different. A third shows the history comes back newest first.

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

The two broker tests are quoted in the RabbitMQ note: a confirmed publish leaves one persistent message in the right queue and a dead letter carries its reason; and a message that cannot be read goes to `rejected` while the service keeps working (`Awaitility` waits up to 60 seconds for the effect).

### `ReplaceFileDropE2E.java`: the lesson's scenario

```java
/**
 * The lesson's scenario, in order, against the real SFTP server, broker and database, with the flow
 * and the order service running inside the test's JVM: one file through the flow, a resend, a
 * restart of the flow, the order service draining the queues, the legacy batch and the
 * reconciliation, the planted difference, and a flow stopped in the middle of a file.
 */
@Tag("e2e")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReplaceFileDropE2E {

  private static final Duration LONG = Duration.ofMinutes(6);

  @TempDir static Path folder;

  private static Path inbox;

  @BeforeAll
  static void clean() {
    inbox = folder.resolve("inbox");
    TestStack.resetDatabase();
    try (ConfigurableApplicationContext context = TestStack.start(folder.resolve("cleaner"))) {
      context.getBean(SftpPartnerDrop.class).clear();
      TestStack.purgeQueues(context.getBean(RabbitAdmin.class));
    }
  }

  private void upload(ConfigurableApplicationContext context, int night, String name)
      throws Exception {
    Path file = folder.resolve(name);
    new GeneratePartnerFile().write(file, night, night);
    context.getBean(SftpPartnerDrop.class).upload(file);
  }

  private int published(RabbitAdmin admin) {
    return TestStack.depth(admin, "retail") + TestStack.depth(admin, "wholesale");
  }
```

`@Tag("e2e")` and an ordered class. `@TempDir static Path folder` is one folder shared by all tests of the class. `@BeforeAll` runs once: it resets the database, starts a context just to clear the SFTP folder and purge the queues, and closes it. `upload` generates a file for a given night and uploads it. `published` is the number of messages waiting in the two order queues.

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

  @Test
  @Order(2)
  void the_same_file_under_a_new_name_adds_no_order_and_the_dead_letter_queue_does_not_grow()
      throws Exception {
    try (ConfigurableApplicationContext flow = TestStack.start(inbox, "flow")) {
      RabbitAdmin admin = flow.getBean(RabbitAdmin.class);
      FlowCounters counters = flow.getBean(FlowCounters.class);
      upload(flow, 1, "orders-1-again.csv");
      Awaitility.await()
          .atMost(LONG)
          .until(() -> counters.duplicateCount() == 9997 + 3 && counters.publishedCount() == 0);
      assertThat(published(admin)).isEqualTo(9997);
      assertThat(TestStack.depth(admin, "dead-letter")).isEqualTo(3);
    }
  }

  @Test
  @Order(3)
  void the_flow_remembers_what_it_saw_across_a_restart() throws Exception {
    try (ConfigurableApplicationContext flow = TestStack.start(inbox, "flow")) {
      RabbitAdmin admin = flow.getBean(RabbitAdmin.class);
      FlowCounters counters = flow.getBean(FlowCounters.class);
      upload(flow, 1, "orders-1-third.csv");
      Awaitility.await()
          .atMost(LONG)
          .until(() -> counters.duplicateCount() == 9997 + 3 && counters.publishedCount() == 0);
      assertThat(published(admin)).isEqualTo(9997);
      assertThat(TestStack.depth(admin, "dead-letter")).isEqualTo(3);
    }
  }
```

Test 1: start the flow, upload night 1, wait until the dead-letter queue holds 3 messages and the order queues together hold 9,997. Test 2: start the flow again (a restart: a new context), upload the same orders under a new name, and wait until the counters show 9,997 + 3 duplicates and 0 published; the queues are unchanged and the dead-letter queue did not grow. Test 3: a third copy after another restart gives the same result, so the flow remembers across restarts.

```java
  @Test
  @Order(4)
  void the_order_service_drains_the_queues_into_the_new_table_exactly_once() {
    try (ConfigurableApplicationContext service = TestStack.start(inbox, "order-service")) {
      RabbitAdmin admin = service.getBean(RabbitAdmin.class);
      Awaitility.await().atMost(LONG).until(() -> published(admin) == 0);
      Awaitility.await()
          .atMost(LONG)
          .until(
              () ->
                  TestStack.jdbc().queryForObject("SELECT count(*) FROM orders_events", Long.class)
                      == 9997);
      assertThat(
              TestStack.jdbc()
                  .queryForObject("SELECT count(DISTINCT order_id) FROM orders_events", Long.class))
          .isEqualTo(9997);
    }
  }

  @Test
  @Order(5)
  void the_legacy_batch_and_the_new_path_reconcile_with_no_difference_and_a_planted_one_is_found() {
    try (ConfigurableApplicationContext tools = TestStack.start(inbox)) {
      LegacyBatchLoader.Result loaded = tools.getBean(LegacyBatchLoader.class).loadNew();
      assertThat(loaded.files()).isEqualTo(3);
      assertThat(TestStack.jdbc().queryForObject("SELECT count(*) FROM orders_batch", Long.class))
          .isEqualTo(9997);
      ReconcileOrders.Outcome clean = tools.getBean(ReconcileOrders.class).run();
      assertThat(clean.report())
          .hasToString(
              "ReconciliationReport[onlyInBatch=0, onlyInEvents=0, different=0, same=9997]");
      TestStack.jdbc()
          .update("UPDATE orders_events SET amount = amount + 1 WHERE order_id = 'N01O000042'");
      ReconcileOrders.Outcome dirty = tools.getBean(ReconcileOrders.class).run();
      assertThat(dirty.report().different()).isEqualTo(1);
      assertThat(dirty.differences()).singleElement().asString().startsWith("N01O000042 different");
      assertThat(dirty.exitCode()).isEqualTo(1);
    }
  }
```

Test 4: start the order service and wait until both queues are empty and the table holds 9,997 rows, 9,997 distinct. Test 5: with no profile (tools only) the legacy loader loads the 3 files into `orders_batch`; the reconciliation is `ReconciliationReport[onlyInBatch=0, onlyInEvents=0, different=0, same=9997]`; then one amount is changed by SQL (`amount + 1` for `N01O000042`) and the next reconciliation finds exactly one difference, lists it, and exits with 1.

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
    assertThat(
            TestStack.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM int_metadata_store WHERE region = 'orders'", Long.class))
        .isLessThan(9997);
    try (ConfigurableApplicationContext flow = TestStack.start(second, "flow", "order-service")) {
      RabbitAdmin admin = flow.getBean(RabbitAdmin.class);
      Awaitility.await()
          .atMost(LONG)
          .until(
              () ->
                  TestStack.jdbc().queryForObject("SELECT count(*) FROM orders_events", Long.class)
                      == 9997);
      Awaitility.await().atMost(LONG).until(() -> published(admin) == 0);
      assertThat(
              TestStack.jdbc()
                  .queryForObject("SELECT count(DISTINCT order_id) FROM orders_events", Long.class))
          .isEqualTo(9997);
    }
  }
```

Test 6: reset, upload a second night, start the flow, stop it (leave the `try` block) as soon as more than 1,500 orders are published (`published(admin) > 1500` polled every 200 ms), check that fewer than 9,997 ids were remembered (the flow did not finish), then start the flow and the order service together and check that all 9,997 orders arrive, the queues drain, and the table has 9,997 distinct ids: nothing lost, nothing doubled.

## Try it yourself

1. Run the unit tests: `make test`. Expect a passing coverage check.
2. Run one class: `./mvnw -q test -Dtest=DomainTest`, then read `target/surefire-reports/plainround.orderflow.domain.DomainTest.txt`.
3. Break a rule: in `OrderParser.java` change `signum() <= 0` to `signum() < 0`; the zero-amount row of the parameterized test fails with `expected: "amount must be above zero"`. Undo it.
4. Run the integration tests: `make up db` then `make test-integration`.
5. Run the end-to-end scenario: `make test-e2e` (several minutes). Do not run `make apps` at the same time: a flow container would compete with the test's own flow for the same SFTP folder.

## Mistakes people make

- **Tests that depend on each other by accident.** JUnit may run methods in any order. This repository uses an explicit order only for the scenario test, and says so.
- **Using the real clock or randomness.** The result changes from run to run. The code takes a `Clock`, and the random test seeds `new Random(11)`.
- **Testing the framework instead of the behavior.** A test that only checks that Spring injected a bean proves little. These tests call real code and assert real answers.
- **A test that passes when nothing runs.** Failsafe ignores classes it does not recognise by name; a misnamed class "passes" by never running. The `includes` in `pom.xml` and the line `Tests run: ...` in the report are how this repository checks the tests ran.
- **Leaving data behind.** Integration tests here use their own database (`orderflow_test`) and clear what they changed in `@BeforeEach` and `@AfterAll`.

Specific to this repository: running the tests while the `flow` or `order-service` containers are up. The containers consume the same folder and queues as the test's own contexts, so counts are off. Run `docker compose --profile apps stop flow order-service` first.

## Check yourself

1. How do integration tests differ from unit tests here? *They use the real database and broker; Failsafe runs them, tagged `integration` or `e2e`.*
2. What does `@CsvSource(delimiter = '|')` do? *Runs the test once per row, with `|` between the columns.*
3. Why are the end-to-end tests ordered? *Each step depends on the one before: upload, resend, restart, drain, reconcile, interrupt.*
4. Why do the tests pass settings as command-line arguments? *They outrank `application.yml`; default properties would not.*
5. Why can the tests not run next to `make apps`? *The containers consume the same SFTP folder and queues.*

## Further reading

- JUnit 5 user guide (Jupiter 5.12, pinned through Spring Boot 3.5): https://junit.org/junit5/docs/5.12.2/user-guide/
- Maven Surefire plug-in: https://maven.apache.org/surefire/maven-surefire-plugin/
- Maven Failsafe plug-in: https://maven.apache.org/surefire/maven-failsafe-plugin/
