# AssertJ

## What it is

AssertJ is a library of **assertions**: statements in a test that say what must be true. You write `assertThat(actual).isEqualTo(expected)` and, if the claim is false, AssertJ throws an error whose message shows both values. Compared with JUnit's own `assertEquals`, AssertJ reads left to right like a sentence and has methods for lists (`hasSize`, `containsExactly`), text (`contains`, `startsWith`), numbers (`isZero`, `isGreaterThanOrEqualTo`) and exceptions (`assertThatThrownBy(...).hasMessageContaining(...)`).

## Why this repository uses it

Every assertion in the tests is AssertJ. The chained style reads like a sentence ("the single difference starts with `N01O000042 different`"), and a failure shows both values.

## Where it appears

| Path | What it does |
| --- | --- |
| Every test class under `src/test/java/plainround/orderflow/` | `assertThat`, `assertThatThrownBy`. |

## How it works underneath

`assertThat(x)` looks at the **type** of `x` and returns the matching assertion object (one for lists, one for strings, one for numbers). Each method on it checks one thing and returns the same object, which is why calls chain: `assertThat(text).contains("a").contains("b")`. The first failing check throws `AssertionError`, which JUnit reports as a failure. `.as("label")` adds a description to the failure message. `assertThatThrownBy(() -> code)` runs the lambda and captures the exception it throws, so you can check its type and message; if no exception is thrown the test fails.

## Walkthrough of this repository's use

The tests are walked through in the JUnit note; this note lists the AssertJ calls with one quoted example each.

```java
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

`assertThat(optional).contains(value)` checks an `Optional` holds that value; `.get().extracting(Order::type).isEqualTo(...)` unwraps it and checks one property.

```java
  @ParameterizedTest
  @NullAndEmptySource
  @CsvSource({"barter", "RETAILS"})
  void an_unknown_type_is_empty(String field) {
    assertThat(OrderType.parse(field)).isEmpty();
  }
```

`assertThat(optional).isEmpty()` on an `Optional`.

```java
  @Test
  void a_policy_needs_at_least_one_night() {
    assertThatThrownBy(() -> new RetirementPolicy(0)).hasMessageContaining("at least 1");
  }
```

`assertThatThrownBy(() -> new RetirementPolicy(0)).hasMessageContaining("at least 1")` checks that the code throws and that the message contains the text.

```java
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

`isZero()` on a number, `isEmpty()` on a list, `isFalse()` on a boolean, `hasSize(1)` on a list.

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
```

`hasSize(10_002)`, `isEqualTo(...)` on text, and a chain over a stream's `count()`.

```java
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

`doesNotContainAnyElementsOf(second)` asserts that two collections have nothing in common.

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
```

`hasSize(3)` followed by three `anyMatch(...)` conditions on a list of texts: each says "some element satisfies this".

```java
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

`containsExactly(a, b)` checks the elements and their order.

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

`containsEntry("reason", "amount missing")` on a map of message headers; `isNotNull()`, `isZero()`.

```java
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

`hasToString(...)` compares an object's text form; `singleElement().asString().startsWith(...)` checks a list with exactly one element.

## Try it yourself

1. See a failure message: in `DomainTest.a_policy_needs_at_least_one_night` change `"at least 1"` to `"at least 2"` and run `./mvnw -q test -Dtest=DomainTest`; the message shows the actual text. Undo it.
2. Find every use: `grep -rn "assertThat" src/test | wc -l`.
3. Read a chained text check: `grep -n "startsWith" src/test/java/plainround/orderflow/e2e/ReplaceFileDropE2E.java`.
4. Replace `containsExactly` with `containsExactlyInAnyOrder` in `StoresAndBrokerIT` and see it still passes; with the wrong order it would fail only with `containsExactly`. Undo it.
5. List the library's assertion classes: `unzip -l ~/.m2/repository/org/assertj/assertj-core/*/assertj-core-*.jar | grep "api/Abstract.*Assert.class" | head`.

## Mistakes people make

- **Forgetting the final check.** `assertThat(x)` alone checks nothing; it must be followed by a method.
- **Comparing floating-point numbers with `isEqualTo`.** Use `isCloseTo` or exact decimals. This repository's money is `BigDecimal`.
- **Asserting on a message too exactly.** A test that repeats a long message breaks on every wording change; use `hasMessageContaining` for the essential words.

## Check yourself

1. What does `containsExactly` require? *Exactly those elements, in that order.*
2. How do you test that code throws? *`assertThatThrownBy(() -> code)` then check the type or message.*
3. What does `anyMatch` assert on a list? *At least one element satisfies the condition.*
4. What does `singleElement()` assert? *The list has exactly one element, and it then asserts on that element.*
5. Why is `assertThat(x)` alone a bug? *It performs no check until a method follows.*

## Further reading

- AssertJ core documentation: https://assertj.github.io/doc/
- AssertJ Core API (the version Spring Boot 3.5 manages, 3.27): https://www.javadoc.io/doc/org.assertj/assertj-core/3.27.3/
