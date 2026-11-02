# Lombok

## What it is

Lombok is a Java library that writes repetitive code for you while the program is being compiled. You put a short label (an **annotation**) on a class, and Lombok adds the missing methods to the compiled result. For example, a class with three `final` fields needs a constructor that takes three values and stores them; writing it by hand is boring and easy to get wrong. With `@RequiredArgsConstructor` you write nothing, and the compiler output contains the constructor.

How is that possible? The Java compiler allows plug-ins called **annotation processors** that run during compilation and may generate or change code. Lombok is one. Because the generated code only exists in the compiled `.class` file, you do not see it in the `.java` file; an editor needs a Lombok plug-in to know about it.

## Why this repository uses it

Two pieces of repeated code would otherwise appear in many classes: constructors that store each dependency in a field, and the line that creates a logger. Lombok writes them at compile time, which keeps the classes short and removes a place for mistakes.

## Where it appears

| Path | What it does with Lombok |
| --- | --- |
| `${J}/application/ProcessOrderMessage.java` | `@Slf4j` and `@RequiredArgsConstructor`. |
| `${J}/application/ReconcileOrders.java` | `@RequiredArgsConstructor`. |
| `${J}/infrastructure/amqp/ConfirmedPublisher.java`, `OrderListener.java` | `@RequiredArgsConstructor`. |
| `${J}/infrastructure/flow/OrderFlowConfiguration.java`, `FileSummary.java` | `@Slf4j` (and `@RequiredArgsConstructor` on `FileSummary`). |
| `${J}/infrastructure/jdbc/JdbcOrderStore.java`, `JdbcReconciliation.java`, `LegacyBatchLoader.java` | `@RequiredArgsConstructor` (and `@Slf4j` on the loader). |
| `${J}/interfaces/OrderflowRunner.java` | `@RequiredArgsConstructor`. |
| `lombok.config` | Marks generated code so tools skip it. |
| `pom.xml` | The `provided` dependency and the annotation processor path. |

## How it works underneath

In `pom.xml` Lombok is a dependency with scope `provided`: it is needed to compile but is not packaged in the final program, since the generated code no longer needs the library. Newer JDKs (23 and later) no longer run annotation processors found on the classpath automatically, so `pom.xml` also names Lombok in the compiler plug-in's `annotationProcessorPaths`; without that, the build would fail with "no constructor" or "cannot find symbol `log`" errors.

The two annotations used here:

- `@RequiredArgsConstructor` generates a constructor with one parameter for every `final` field that has no initial value (and for fields marked `@NonNull`). That makes **constructor injection** (Spring passes dependencies through the constructor) trivial to write and keeps the fields immutable.
- `@Slf4j` generates the line `private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TheClass.class);`, so any method can write `log.info("...")`. SLF4J is the logging façade (see the Logback note).

## Walkthrough of this repository's use

### `lombok.config`

```
config.stopBubbling = true
lombok.addLombokGeneratedAnnotation = true
lombok.log.fieldName = log
```

`lombok.addLombokGeneratedAnnotation = true` adds the annotation `@lombok.Generated` to everything Lombok writes. JaCoCo (the coverage tool) skips classes and methods carrying that annotation, so generated code does not distort the coverage figure.

### `pom.xml`

```xml
    <dependency>
      <groupId>org.projectlombok</groupId>
      <artifactId>lombok</artifactId>
      <scope>provided</scope>
    </dependency>
```

The Lombok dependency has scope `provided`: needed to compile, never packaged. The annotation processor is switched on in the compiler plug-in (see the Maven note).

### `@RequiredArgsConstructor`

```java
@Slf4j
@RequiredArgsConstructor
public class ProcessOrderMessage {

  /** What a delivery did. */
  public enum Outcome {
    STORED,
    DUPLICATE
  }

  private final OrderStore store;

```

`@RequiredArgsConstructor` writes `public ProcessOrderMessage(OrderStore store)`: a constructor with one parameter for every `final` field that is not yet assigned, storing each. Spring uses that constructor to pass in the dependencies. `@Slf4j` adds `private static final org.slf4j.Logger log = LoggerFactory.getLogger(ProcessOrderMessage.class);`, so the class can write `log.debug(...)`.

```java
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

```

Here `@RequiredArgsConstructor` writes `ReconcileOrders(ReconciliationSource source, ReconciliationHistory history, RetirementPolicy policy)` in the order the fields are declared. The static constant `LISTED_DIFFERENCES` is not `final` instance state, so it is not part of the constructor. The nested `record Outcome` is not affected.

```java
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class ConfirmedPublisher {

  private final RabbitTemplate template;
  private final long confirmTimeoutMillis;

  private final RetryTemplate brokerAway;

  /** Tries again every two seconds for up to five minutes while the broker is away. */
  public ConfirmedPublisher(RabbitTemplate template, long confirmTimeoutMillis) {
    this(template, confirmTimeoutMillis, whileBrokerAway(Duration.ofSeconds(2)));
  }
```

`@RequiredArgsConstructor(access = AccessLevel.PACKAGE)` writes the three-argument constructor `ConfirmedPublisher(RabbitTemplate template, long confirmTimeoutMillis, RetryTemplate brokerAway)` and makes it visible only inside the package (`AccessLevel.PACKAGE`; the default is `PUBLIC`). The two-argument constructor below it is written by hand and is the one `OrderFlowConfiguration` calls; it fills in the retry rule and calls the generated one with `this(...)`.

```java
@Component
@RequiredArgsConstructor
public class OrderflowRunner implements ApplicationRunner, ExitCodeGenerator {

  private final GeneratePartnerFile generator;
  private final SftpPartnerDrop drop;
  private final LegacyBatchLoader legacy;
  private final ReconcileOrders reconcile;
  private final JdbcTemplate jdbc;
  private final AmqpAdmin admin;
  private volatile int exitCode;
```

The runner has six `final` fields and no hand-written constructor; Lombok writes the six-argument constructor. The field `exitCode` is not `final`, so it is not a parameter.

## Try it yourself

1. See what Lombok generated: `./mvnw -q -DskipTests package` then `javap -p -cp target/classes plainround.orderflow.application.ProcessOrderMessage` lists the constructor `ProcessOrderMessage(plainround.orderflow.application.OrderStore)` and the `log` field.
2. Look at the processor setting: `grep -n -B2 -A4 lombok pom.xml`.
3. Add a field: add `private final String name;` to `ConfirmedPublisher`; `OrderFlowConfiguration` no longer compiles because the generated constructor now needs it. Undo it.
4. Show the marker: `javap -v -cp target/classes plainround.orderflow.application.ProcessOrderMessage | grep -i generated` finds the `lombok.Generated` annotation.
5. Compare without Lombok: write out the constructor of `ReconcileOrders` by hand; it is nine lines for what one annotation does.

## Mistakes people make

- **Forgetting the annotation-processor path on a new JDK.** The build then fails with errors about missing constructors or the missing `log` variable. This repository names Lombok under `annotationProcessorPaths` in `pom.xml`.
- **Using `@Data` or setters on domain objects.** They make objects mutable. This repository uses records for data and Lombok only for constructors and loggers.
- **Reading the `.java` file and not finding the constructor.** It is generated; use `javap` on the `.class` file to see it.
- **Field order.** `@RequiredArgsConstructor` takes parameters in field order; reorder the fields and every `new ...(a, b)` call changes meaning. Wiring code that uses `new` directly must follow the field order.

## Check yourself

1. What does `@RequiredArgsConstructor` include? *Every `final` field that has no value yet.*
2. What is `@Slf4j` for? *It adds a `log` field for the class.*
3. Why `lombok.addLombokGeneratedAnnotation`? *So coverage tools ignore generated code.*
4. Why is the dependency `provided`? *Needed at compile time only.*
5. Does Lombok change the source files? *No; it changes the compiled classes.*

## Further reading

- Lombok features overview: https://projectlombok.org/features/
- `@RequiredArgsConstructor`: https://projectlombok.org/features/constructor
- `@Slf4j` (log): https://projectlombok.org/features/log
- Configuration keys (`lombok.config`): https://projectlombok.org/features/configuration
