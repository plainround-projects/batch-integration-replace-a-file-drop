# Instancio

## What it is

Instancio is a Java library that creates **test objects filled with random values**. Writing the data for a test by hand (`new Order("A1", "Ann", 12.5, ...)`) is slow and hides which fields matter. With Instancio you write `Instancio.create(Order.class)` and get a complete object; you set only the fields the test cares about (`Instancio.of(Order.class).set(field(Order::status), "PAID").create()`). Because the data is random, tests also catch code that accidentally depends on a particular value. Instancio prints the **seed** of every failing test so you can replay the exact data.

## Why this repository uses it

The project rule for tests is "Instancio for test data". It is pinned in `pom.xml` through the property `instancio.version` (5.5.1), because Spring Boot does not manage it.

## Where it appears

| Path | What it does with Instancio |
| --- | --- |
| `pom.xml` | The property `instancio.version` and the test-scoped dependency `org.instancio:instancio-junit`. |

The tests in this repository use fixed, readable lines on purpose (for example `O999002,C0002,retail,-4` must be refused with `amount must be above zero`), because the exact text *is* the specification of the parser. The library is pinned for tests where the exact values do not matter.

## How it works underneath

Instancio uses Java's reflection (the ability of a program to inspect classes and fields at run time) to find the fields of the class you ask for, and fills each with a random value of the right type: numbers, strings, dates, collections, nested objects. A **generator** controls values (`gen.string().length(5)`); a **selector** (`field(...)`, `allInts()`) picks which fields a rule applies to. The JUnit extension `instancio-junit` seeds the random generator per test and reports the seed on failure; `@InstancioSource` can feed generated objects into a parameterized test.

## Walkthrough of this repository's use

```xml
    <archunit.version>1.4.1</archunit.version>
    <instancio.version>5.5.1</instancio.version>
    <result4j.version>1.2.0</result4j.version>
```

Line 24 (`archunit.version`), 25 (`instancio.version`) and 26 (`result4j.version`) are the versions of libraries outside Spring Boot's managed list, kept in properties so each version appears once.

```xml
    <dependency>
      <groupId>org.instancio</groupId>
      <artifactId>instancio-junit</artifactId>
      <version>${instancio.version}</version>
      <scope>test</scope>
    </dependency>
```

The dependency: the artifact `instancio-junit` brings the core library and the JUnit 5 extension; the version comes from the property; `<scope>test</scope>`.

An example of how a test would use it (not code from this repository):

```java
Order order = Instancio.of(Order.class)
    .set(field(Order::type), OrderType.RETAIL)
    .create();
```

It builds an `Order` with a random order id, customer and amount and the type fixed to `RETAIL`.

## Try it yourself

1. Find the version: `grep -n instancio pom.xml`.
2. Inspect the jar: `unzip -l ~/.m2/repository/org/instancio/instancio-junit/5.5.1/instancio-junit-5.5.1.jar | head`.
3. Write a throwaway test that prints `Instancio.create(plainround.orderflow.domain.Order.class)` three times and run it; note the values differ each run. Delete it afterwards.
4. Reproduce a run: Instancio prints `Seed: 123` on a failure; set `-Dinstancio.seed=123` (or `@Seed(123)`) and the same data comes back.
5. Search for current uses: `grep -rn "Instancio" src` prints nothing.

## Mistakes people make

- **Random data where the exact value is the point.** A rule such as "round down to whole units" is better shown with chosen numbers.
- **Not replaying the seed.** A failure that "only happens sometimes" is reproducible once you set the printed seed.
- **Filling objects that have validation rules** without constraining the fields; set the fields that rules check.

## Check yourself

1. What does `Instancio.create(X.class)` return? *An `X` with all fields filled with random values.*
2. How do you reproduce a failing random test? *Reuse the seed that Instancio printed.*
3. Why are the tests here written with fixed lines? *The exact text of a line is the specification of the parser.*
4. Which mechanism lets Instancio fill fields it has never seen? *Reflection.*
5. Why is the version in a property? *Spring Boot does not manage it, and one place avoids drift.*

## Further reading

- Instancio user guide: https://www.instancio.org/user-guide/
- Instancio 5.5.1 on Maven Central: https://central.sonatype.com/artifact/org.instancio/instancio-junit/5.5.1
