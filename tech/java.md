# Java 21

## What it is

Java is a programming language. A programming language is a way of writing instructions that a computer can follow. You write the instructions in plain text files (here they end in `.java`). A program called the **compiler** (`javac`) turns those text files into **bytecode**, which is a compact set of instructions saved in `.class` files. Another program, the **Java Virtual Machine** (the JVM, started with the command `java`), reads the bytecode and runs it. The JVM is the same on a Mac, a Windows laptop and a Linux server, which is why Java programs run almost anywhere.

Three words you will meet again and again:

- A **class** is a named bundle of data and the actions (called **methods**) that work on that data.
- A **package** is a folder-like name that groups classes, written with dots, such as `plainround.orderflow.domain`. The folder on disk has the same shape: `plainround/orderflow/domain/`.
- A **JDK** (Java Development Kit) is the download that contains the compiler, the JVM and the standard library (thousands of ready-made classes such as `List`, `Duration` and `BigDecimal`).

This repository is written for **Java 21**, an LTS release (LTS means "long-term support": the version that companies keep using for years). It is built and run here with a newer JDK, but the compiler is told to produce Java 21 code (`maven.compiler.release` is 21 in `pom.xml`), so everything in the notes is true for Java 21.

## Why this repository uses it

The flow, the broker client and the database code are framework code, but the rules are not: what makes a line of the partner's file a valid order, which queue an order goes to, when two paths agree, and when the old path may be retired are plain Java with no framework imports. That keeps every rule testable on its own and lets a reader see the rules without reading any framework.

## Where it appears

| Path | What it does with Java |
| --- | --- |
| `${J}/domain/` | The rules: an order, parsing a line, the order type, a rejection, the reconciliation report, the retirement policy. |
| `${J}/application/` | The use cases (`ProcessOrderMessage`, `ReconcileOrders`, `GeneratePartnerFile`) and the ports they need. |
| `${J}/infrastructure/` | Spring Integration, RabbitMQ, SFTP and JDBC code. Walked through in their own notes. |
| `${J}/interfaces/` | The program's entry point and command-line runner. Walked through in the Spring Boot note. |
| `src/test/java/` | The tests. Walked through in the JUnit note. |
| `pom.xml` | Tells Maven to compile for Java 21. |

## How it works underneath

When you run `java -jar app.jar` an operating-system **process** starts. A process is one running copy of a program, with its own memory. Inside that process the JVM loads the bytecode, checks it, and then runs it. The JVM starts by interpreting bytecode and, for the code that runs often, compiles it to the machine's own instructions while the program is running (this is called JIT, "just in time" compilation). That is why a Java program is slow for the first second and fast afterwards.

Memory is split in two main areas. The **stack** holds the small, short-lived values of the method that is running right now (a method is one named block of instructions); the **heap** holds the objects, which are the bundles of data created with `new`. A part of the JVM called the **garbage collector** finds objects that nothing refers to any more and frees their memory, so you never free memory by hand.

Java is **statically typed**: every variable has a type (`long`, `String`, `BigDecimal`) that is fixed when you write the code, and the compiler refuses a program that mixes types wrongly. The main numeric types you will see: `int` (a 32-bit whole number), `long` (a 64-bit whole number), `double` (a floating-point number, which is only approximate), and `BigDecimal` (an exact decimal number, used for money because `double` cannot represent 0.10 exactly).

Code is organised in **packages** and **imports**. A line `import java.util.List;` tells the compiler that the short name `List` means `java.util.List`. Names from `java.lang` (such as `String`, `Math`, `IllegalArgumentException`) need no import.

A **thread** is one line of execution inside a process. A program can run several threads at the same time (on a machine with several processor cores they really run in parallel). Threads share the heap, which is powerful and dangerous: two threads changing the same object at the same moment can corrupt it. This is why this repository prefers **immutable** objects (objects that can never change after they are created).

## Walkthrough of this repository's use

Language features this repository uses, explained once here and pointed to later:

- **Record.** `public record Account(long accountId, String tier) {}` declares a class whose only job is to carry two values. The compiler writes for you: a constructor that takes both values, a getter method for each value (named like the field, `account.tier()`, with no `get` prefix), and `equals`, `hashCode` and `toString` (two accounts with the same values are equal; printing one shows both values). The fields are `final`, which means they cannot change after construction: a record is immutable.
- **Compact constructor.** Inside a record you can write `public BillingMonth { ... }` with no parameter list. It runs before the fields are assigned, so it is the place to check the values and throw an exception if they are wrong.
- **Exception.** A way for a method to say "I cannot continue". `throw new IllegalArgumentException("...")` stops the method and unwinds to whoever can handle it; if nobody does, the program or thread ends with a message. `IllegalArgumentException` is the standard exception for "the caller gave me a bad value".
- **`final` class and private constructor.** `public final class OrderParser { private OrderParser() {} }` is the usual way to write a holder of functions that should never be instantiated: `final` forbids subclasses and the private constructor forbids `new OrderParser()`.
- **`static`.** A static method belongs to the class, not to an object, so you call it as `OrderParser.parse(...)` without creating anything.
- **Generics.** `List<AccountRange>` is "a list whose elements are all `AccountRange`". The angle brackets let the compiler check what goes in and what comes out.
- **Lambda.** `index -> min + index * size <= max` is a small unnamed function: the part before `->` is the parameter, the part after is the result. Lambdas are how you hand a piece of behaviour to another method.
- **Stream.** `LongStream.range(0, parts).filter(...).mapToObj(...).toList()` is a pipeline: produce the numbers 0 to parts-1, keep some, convert each to another kind of value, collect the results. Each step receives the output of the step before it.
- **Text formatting.** `"minId %d is above maxId %d".formatted(minId, maxId)` replaces each `%d` with the next number; `%s` is for text; `%ds` in `"projected %ds"` is `%d` (a number) followed by a literal `s`.
- **Javadoc.** A comment that starts with `/**` documents the next class or method. Tools turn it into web pages; people read it in the editor.
- **Duration.** `java.time.Duration` is an exact length of time (seconds and nanoseconds). Using it instead of a bare number prevents "was that milliseconds or seconds?" bugs.

More features appear in this repository; each is explained where it is first used below: `enum` with a method, `Optional` chains, `switch`-free `record` accessors, regular expressions, `Stream.of`, `Random` with a seed, and `try`/`catch` that rethrows an unchecked exception.

### The domain package: the rules

**`Order.java`** is a valid order.

```java
package plainround.orderflow.domain;

import java.math.BigDecimal;

/** One valid order from the partner. The partner's order id is the business key at every hop. */
public record Order(String orderId, String customer, OrderType type, BigDecimal amount) {}
```

A record with four components: the partner's order id (text), the customer (text), the type, and the amount (`BigDecimal`, an exact decimal; `double` would give `0.1 + 0.2 = 0.30000000000000004`). The Javadoc says the order id is the business key at every hop: the same id identifies the order in the file, in the idempotent receiver's memory, in the broker message and in both database tables.

**`OrderType.java`** is the two kinds of order.

```java
package plainround.orderflow.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * The two kinds of order the partner sends; the routing key and the queue are the lower-case name.
 */
public enum OrderType {
  RETAIL,
  WHOLESALE;

  /** The queue (and routing key) for this type. */
  public String queue() {
    return name().toLowerCase();
  }

  /** The type named by a file field, ignoring case; empty for anything else. */
  public static Optional<OrderType> parse(String field) {
    return Arrays.stream(values())
        .filter(type -> type.queue().equalsIgnoreCase(field == null ? "" : field.strip()))
        .findFirst();
  }
}
```

An **enum** is a type with a fixed list of values: `RETAIL` and `WHOLESALE`. An enum can have methods. `queue()` returns `name().toLowerCase()`: `name()` is the constant's identifier as text (`"RETAIL"`), so the queue and the routing key are `retail` and `wholesale`. `parse` turns a file field into a type: `Arrays.stream(values())` streams every constant (`values()` is generated by the compiler), `filter` keeps those whose queue name equals the field ignoring case (`equalsIgnoreCase`; a `null` field is treated as the empty text, and `strip()` removes surrounding spaces), and `findFirst()` gives an `Optional`: empty when nothing matched. An **`Optional`** is a box that holds a value or nothing, so "no such type" is a value rather than a `null`.

**`Rejection.java`** is the reason a line was refused.

```java
package plainround.orderflow.domain;

/** A line that cannot become an order, with the reason: what the dead-letter channel carries. */
public record Rejection(String line, String reason) {}
```

A record of the original line and the reason. It is what the dead-letter channel carries.

**`OrderParser.java`** turns a line into an order or a rejection.

```java
package plainround.orderflow.domain;

import com.github.sviperll.result4j.Result;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The Message Translator and the Message Filter of the flow, as one pure function: a line of the
 * partner's file becomes an {@link Order}, or a {@link Rejection} saying why not. No framework, so
 * every rule is testable on its own.
 */
public final class OrderParser {

  private static final int FIELDS = 4;

  private OrderParser() {}

  /** Parses {@code order_id,customer,type,amount}. The first rule that fails names the reason. */
  public static Result<Order, Rejection> parse(String line) {
    List<String> fields = Arrays.stream(line.split(",", -1)).map(String::strip).toList();
    return fields.size() != FIELDS
        ? reject(line, "malformed line: expected 4 fields")
        : build(line, fields);
  }
```

The comment names two enterprise-integration patterns (see the Spring Integration note): the **Message Translator** (a line becomes an order) and the **Message Filter** (a bad line is kept out), done in one pure function. The imports show `Result` (see the Result4J note): a value that is a success or an error. `FIELDS = 4` is a constant (`static final`: one value shared by the class, never changed). The private constructor on line 19 stops anyone from creating an instance; the class only holds functions. `parse` splits the line with `line.split(",", -1)`: the second argument `-1` keeps trailing empty fields (without it `"O1,C1,retail,"` would give three pieces, hiding the missing amount), `.map(String::strip)` removes spaces around each piece, `.toList()` collects them. Then a **ternary**: `condition ? a : b` evaluates to `a` when the condition is true. If there are not exactly four fields the result is a rejection with the reason `malformed line: expected 4 fields`; otherwise `build` continues.

```java
  private static Result<Order, Rejection> build(String line, List<String> fields) {
    Optional<OrderType> type = OrderType.parse(fields.get(2));
    return problem(fields, type)
        .<Result<Order, Rejection>>map(reason -> reject(line, reason))
        .orElseGet(
            () ->
                Result.success(
                    new Order(
                        fields.get(0),
                        fields.get(1),
                        type.orElseThrow(),
                        new BigDecimal(fields.get(3)))));
  }

  private static Optional<String> problem(List<String> fields, Optional<OrderType> type) {
    return Stream.of(
            Optional.of("order id missing").filter(reason -> fields.get(0).isEmpty()),
            Optional.of("customer missing").filter(reason -> fields.get(1).isEmpty()),
            Optional.of("unknown order type " + fields.get(2)).filter(reason -> type.isEmpty()),
            amountProblem(fields.get(3)))
        .flatMap(Optional::stream)
        .findFirst();
  }
```

`build` parses the type once, then asks `problem` for the first problem. `problem(fields, type).<Result<Order, Rejection>>map(reason -> reject(line, reason))` turns a found reason into a rejection (the `<Result<Order, Rejection>>` before `map` is an explicit **type argument** that tells the compiler what type the lambda returns), and `.orElseGet(() -> Result.success(new Order(...)))` builds the order only when no problem was found (`orElseGet` takes a function so the order is created only when needed). `type.orElseThrow()` unwraps the type, which is safe because `problem` has already reported a missing type. `new BigDecimal(fields.get(3))` is safe for the same reason: the amount rules have passed. `problem` lists the rules in the order the lesson wants: `Stream.of(a, b, c, d)` makes a stream of four `Optional<String>` values, each is either a reason or empty (`Optional.of("order id missing").filter(reason -> fields.get(0).isEmpty())` keeps the reason only when the id field is empty), `.flatMap(Optional::stream)` turns each non-empty optional into one element and drops the empty ones, and `.findFirst()` takes the first reason, so only the first failing rule is reported.

```java
  private static Optional<String> amountProblem(String amount) {
    if (amount.isEmpty()) {
      return Optional.of("amount missing");
    }
    return Optional.of(amount)
        .filter(text -> !text.matches("-?\\d+(\\.\\d{1,2})?"))
        .map(text -> "amount is not a number with at most two decimals")
        .or(
            () ->
                Optional.of(amount)
                    .filter(text -> new BigDecimal(text).signum() <= 0)
                    .map(text -> "amount must be above zero"));
  }

  private static Result<Order, Rejection> reject(String line, String reason) {
    return Result.error(new Rejection(line, reason));
  }
}
```

`amountProblem` checks the amount. An empty field gives `amount missing`. Otherwise `text.matches("-?\\d+(\\.\\d{1,2})?")` tests a **regular expression** (a pattern language for text): `-?` an optional minus sign, `\\d+` one or more digits (in Java source the backslash is written twice), `(\\.\\d{1,2})?` an optional part made of a dot and one or two digits. A text that does not match gets `amount is not a number with at most two decimals`; `.or(() -> ...)` supplies the next check only when the first gave nothing: a number that is zero or below (`signum() <= 0`; `signum` is -1, 0 or 1) gets `amount must be above zero`. `reject` builds the error side of the `Result`.

**`ReconciliationReport.java`** is the morning comparison.

```java
package plainround.orderflow.domain;

/** The four counts of the morning comparison of the two paths. */
public record ReconciliationReport(long onlyInBatch, long onlyInEvents, long different, long same) {

  /** True when the paths agree completely: nothing on one side only, nothing different. */
  public boolean isClean() {
    return onlyInBatch == 0 && onlyInEvents == 0 && different == 0;
  }
}
```

Four counts: orders only in the batch table, only in the events table, present in both but different, and the same. `isClean()` is true only when nothing is on one side only and nothing differs. `long` is a 64-bit whole number.

**`RetirementPolicy.java`** is the condition for retiring the legacy path, as a number.

```java
package plainround.orderflow.domain;

import java.util.List;

/**
 * The condition for retiring the legacy file path, as a number: this many nights in a row with a
 * clean reconciliation. One clean night proves little; a week of files can still hide the one
 * partner behavior that happens on the first of the month.
 */
public record RetirementPolicy(int cleanNightsRequired) {

  public RetirementPolicy {
    if (cleanNightsRequired < 1) {
      throw new IllegalArgumentException("cleanNightsRequired must be at least 1");
    }
  }

  /** True when the latest {@code cleanNightsRequired} reports (newest first) are all clean. */
  public boolean canRetire(List<ReconciliationReport> newestFirst) {
    return newestFirst.size() >= cleanNightsRequired
        && newestFirst.stream().limit(cleanNightsRequired).allMatch(ReconciliationReport::isClean);
  }
}
```

The **compact constructor** on lines 12 to 16 refuses a policy of fewer than one night with `IllegalArgumentException`. `canRetire` takes the reports newest first and is true when there are at least `cleanNightsRequired` of them and the newest that many are all clean: `.limit(n)` keeps the first n, `.allMatch(ReconciliationReport::isClean)` is true when every one is clean (a dirty night in the middle therefore resets the count). `ReconciliationReport::isClean` is a **method reference**: shorthand for the lambda `report -> report.isClean()`.

### The application package: use cases and ports

An **interface** lists methods without code; here each interface is a **port** the use case needs from the outside.

```java
package plainround.orderflow.application;

import plainround.orderflow.domain.Order;

/** The port to the new path's table. */
public interface OrderStore {

  /**
   * Stores the order unless its id exists; true when it was new. Repeating a delivery changes
   * nothing.
   */
  boolean saveIfAbsent(Order order);
}
```

The port to the new path's table: `saveIfAbsent` returns `true` when the order was new and `false` when the id already existed, so repeating a delivery changes nothing.

```java
package plainround.orderflow.application;

import java.nio.file.Path;

/** The port to the partner's SFTP folder. */
public interface PartnerDrop {

  /** Uploads the file into the folder the flow and the legacy loader both read. */
  void upload(Path file);
}
```

The port to the partner's SFTP folder: upload a file (`java.nio.file.Path` is a file location).

```java
package plainround.orderflow.application;

import java.util.List;
import plainround.orderflow.domain.ReconciliationReport;

/** The port to the comparison of the two paths. */
public interface ReconciliationSource {

  /** The four counts. */
  ReconciliationReport compare();

  /** Up to {@code limit} lines describing the orders behind the differences. */
  List<String> differences(int limit);
}
```

```java
package plainround.orderflow.application;

import java.util.List;
import plainround.orderflow.domain.ReconciliationReport;

/** The port to the record of nightly comparisons. */
public interface ReconciliationHistory {

  /** Appends a report. */
  void record(ReconciliationReport report);

  /** The latest reports, newest first. */
  List<ReconciliationReport> latest(int count);
}
```

Two ports for the reconciliation: one compares the two paths and lists the orders behind differences, one records each night's report and returns the latest ones, newest first.

```java
package plainround.orderflow.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import plainround.orderflow.domain.Order;

/**
 * The order service's use case: apply one delivered order. The broker delivers at least once, so
 * the same order can arrive twice (a redelivery after a restart, a message republished by the
 * flow); the store's key makes the second delivery a no-op.
 */
@Slf4j
@RequiredArgsConstructor
public class ProcessOrderMessage {

  /** What a delivery did. */
  public enum Outcome {
    STORED,
    DUPLICATE
  }

  private final OrderStore store;

  /** Stores the order, or notes that it was already there. */
  public Outcome handle(Order order) {
    boolean fresh = store.saveIfAbsent(order);
    log.debug("order {} {}", order.orderId(), fresh ? "stored" : "already stored");
    return fresh ? Outcome.STORED : Outcome.DUPLICATE;
  }
}
```

The order service's use case. `@Slf4j` (a logger named `log`) and `@RequiredArgsConstructor` (a constructor for the one `final` field) come from Lombok. The nested `enum Outcome` has two values, `STORED` and `DUPLICATE`. `handle` stores the order and reports which it was: the broker delivers at least once, so the same order can arrive twice, and the store's key makes the second delivery a no-op. `log.debug("order {} {}", id, ...)` writes a message at debug level; the `{}` are replaced by the arguments (the Logback note explains levels).

```java
package plainround.orderflow.application;

import java.util.List;
import lombok.RequiredArgsConstructor;
import plainround.orderflow.domain.ReconciliationReport;
import plainround.orderflow.domain.RetirementPolicy;

/**
 * The morning job: compare the paths, remember the result, say whether the legacy path may be
 * retired.
 */
@RequiredArgsConstructor
public class ReconcileOrders {

  /** The night's counts, the orders behind any difference, and the cutover verdict. */
  public record Outcome(
      ReconciliationReport report, List<String> differences, boolean mayRetireLegacy) {

    /** 0 for a clean night, 1 for any difference. */
    public int exitCode() {
      return report.isClean() ? 0 : 1;
    }
  }

  private static final int LISTED_DIFFERENCES = 20;

  private final ReconciliationSource source;
  private final ReconciliationHistory history;
  private final RetirementPolicy policy;

  /** Runs the comparison and records it. */
  public Outcome run() {
    ReconciliationReport report = source.compare();
    history.record(report);
    List<String> differences =
        report.isClean() ? List.of() : source.differences(LISTED_DIFFERENCES);
    return new Outcome(
        report, differences, policy.canRetire(history.latest(policy.cleanNightsRequired())));
  }
}
```

The morning job. The nested `record Outcome(report, differences, mayRetireLegacy)` carries the result, and `exitCode()` is 0 for a clean night and 1 for any difference (a script or a scheduler can act on it). `run` compares, records the report, lists up to 20 differences only when the night is not clean, and asks the policy whether the newest reports allow retiring the old path. `LISTED_DIFFERENCES = 20` bounds the output.

```java
package plainround.orderflow.application;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

/**
 * The pretend legacy partner (the lesson's {@code partner.py}). A file of 10,000 order lines, three
 * planted bad ones (no amount, negative amount, unknown type) and one repeated line. {@code night}
 * makes the order ids of different files different, so a week of files is a week of distinct
 * orders.
 */
public class GeneratePartnerFile {

  private static final int ORDERS = 10_000;

  /**
   * Writes the file; returns the number of valid, distinct orders it holds (9,997 for the default
   * shape).
   */
  public int write(Path target, int night, long seed) {
    Random random = new Random(seed);
    List<String> lines = new ArrayList<>(List.of("order_id,customer,type,amount"));
    IntStream.rangeClosed(1, ORDERS)
        .mapToObj(number -> order(night, number, random))
        .forEach(lines::add);
    plantBadLines(lines, night);
    lines.add(lines.get(11));
    write(target, lines);
    return ORDERS - 3;
  }
```

The pretend legacy partner. The constant `ORDERS = 10_000` (underscores in a number are only for reading). `write` creates a `Random` from a **seed** (the same seed gives the same sequence, so the file is reproducible), builds the header line, adds 10,000 order lines (`IntStream.rangeClosed(1, ORDERS)` produces 1 to 10,000, `mapToObj` turns each number into a line, `forEach(lines::add)` collects them), plants three bad lines, repeats one line (`lines.add(lines.get(11))` adds a copy of line 11 at the end: a duplicate), writes the file, and returns `ORDERS - 3`, the number of valid distinct orders (9,997).

```java
  private String order(int night, int number, Random random) {
    String type = random.nextBoolean() ? "retail" : "wholesale";
    BigDecimal amount =
        BigDecimal.valueOf(5 + random.nextDouble() * 895).setScale(2, RoundingMode.HALF_UP);
    return "N%02dO%06d,C%04d,%s,%s".formatted(night, number, 1 + random.nextInt(500), type, amount);
  }

  private void plantBadLines(List<String> lines, int night) {
    lines.set(500, "N%02dX999001,C0001,retail,".formatted(night));
    lines.set(2500, "N%02dX999002,C0002,retail,-4".formatted(night));
    lines.set(7500, "N%02dX999003,C0003,barter,10".formatted(night));
  }

  private void write(Path target, List<String> lines) {
    try {
      Files.write(target, lines);
    } catch (IOException problem) {
      throw new UncheckedIOException(problem);
    }
  }
}
```

`order` builds one line: a random type (`random.nextBoolean()` picks retail or wholesale), a random amount between 5 and 900 rounded to two decimals (`setScale(2, RoundingMode.HALF_UP)`), and `"N%02dO%06d,C%04d,%s,%s".formatted(...)`: `%02d` is a number padded with zeros to two digits, `%06d` to six, `%04d` to four, so night 3, number 42 gives `N03O000042`. The night number is part of the order id, so a week of files is a week of distinct orders. `plantBadLines` replaces lines 500, 2500 and 7500 with a line without an amount, a negative amount, and an unknown type. `write` wraps the checked `IOException` in an `UncheckedIOException`, so callers are not forced to catch it.

## Try it yourself

1. Check your Java: `java -version`.
2. Run the tests of the rules: `./mvnw -q test -Dtest=DomainTest`.
3. Generate a file and look at it: `java -jar target/replace-a-file-drop-0.1.0.jar --command=partner --night=1` needs the services, so instead read the generator's test: `./mvnw -q test -Dtest=ApplicationTest#the_partner_file_has_the_lessons_shape`.
4. Try the parser in `jshell`: `jshell --class-path target/classes`, then `plainround.orderflow.domain.OrderParser.parse("O1,C1,retail,10.5")` prints a success and `plainround.orderflow.domain.OrderParser.parse("O1,C1,barter,10")` prints an error with the reason `unknown order type barter`.
5. See `Optional` at work: in `jshell`, `plainround.orderflow.domain.OrderType.parse(" Wholesale ")` prints `Optional[WHOLESALE]` and `plainround.orderflow.domain.OrderType.parse("barter")` prints `Optional.empty`.

## Mistakes people make

- **Comparing text with `==`.** In Java `==` on two objects asks "are they the very same object?", not "do they hold the same text?". Use `equals`. The code here writes `GOLD.equals(tier)` (constant first), which also avoids a crash when `tier` is `null`.
- **Using `double` for money.** `0.1 + 0.2` is not exactly `0.3` with `double`. Money and prices use `BigDecimal`, and rounding is always chosen on purpose (`RoundingMode.DOWN` here).
- **Forgetting that `int` overflows.** A whole number that grows past its type's limit wraps around silently. Counts of rows here use `long`.
- **Catching an exception and doing nothing.** An empty `catch` hides the reason a program is wrong. This repository lets unexpected exceptions travel upward until something logs them.
- **Sharing a changing object between threads.** Records and `final` fields avoid the problem; mutable shared state needs locks or atomic classes (you will meet `AtomicLong` in the batch notes).
- **Off-by-one in ranges.** `range(0, 8)` produces 0 to 7, not 0 to 8. The sample file generator plants its bad lines by index; the unit test that counts them guards against being off by one.

Specific to this repository: do not use `double` for money (amounts are `BigDecimal` and the table column is `NUMERIC(12,2)`), and do not call `Optional.get()` without checking (the code uses `orElseThrow`, `orElseGet` and `flatMap` instead).

## Check yourself

1. Why does `split(",", -1)` use `-1`? *To keep trailing empty fields, so a line ending in a comma still has four fields and the missing amount is reported.*
2. What does `OrderParser.parse("O1,C1,barter,10")` return? *An error with the reason `unknown order type barter`.*
3. How many reasons does a line with several problems report? *One, the first failing rule in the order id, customer, type, amount order.*
4. What makes a RetirementPolicy of 14 nights reject a week of clean nights? *It needs at least 14 reports, all clean, newest first.*
5. Why is the order id part of the file generator's night number? *So different nights have different order ids and a week of files is a week of distinct orders.*

## Further reading

- Java SE 21 documentation: https://docs.oracle.com/en/java/javase/21/
- The Java Language Specification, Java SE 21 edition: https://docs.oracle.com/javase/specs/jls/se21/html/index.html
- Records (JEP 395): https://openjdk.org/jeps/395
- `java.time.Duration`: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/Duration.html
