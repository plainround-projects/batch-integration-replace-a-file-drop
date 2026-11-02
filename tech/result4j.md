# Result4J

## What it is

Result4J is a very small Java library that provides one type, `Result<T, E>`. A `Result` holds either a **success** value of type `T` or an **error** value of type `E`, never both. It is a way to return "this worked, here is the value" or "this failed, here is why" as an ordinary value, instead of throwing an exception.

Why bother? An exception jumps out of the method and can pass through many callers that do not expect it. A `Result` is part of the method's type, so the caller is forced to look at it. You can then chain steps: `.map(f)` changes the success value and leaves an error alone; `.recoverError(g)` turns an error back into a value of the success type.

## Why this repository uses it

A line of the partner's file may be invalid, and that is an expected outcome, not a bug: the flow must route it to the dead-letter queue and carry on. Returning a `Result<Order, Rejection>` makes the two outcomes visible in the type, and the flow's router can branch on it without catching exceptions.

## Where it appears

| Path | What it does with Result4J |
| --- | --- |
| `${J}/domain/OrderParser.java` | `parse` returns `Result<Order, Rejection>`. |
| `${J}/infrastructure/flow/OrderFlowConfiguration.java` | Routes on `Result::isError`; unwraps the success and the error. |
| `${J}/infrastructure/jdbc/LegacyBatchLoader.java` | Keeps only the successes of a stream of parsed lines. |
| `src/test/java/plainround/orderflow/domain/DomainTest.java` | Reads the reason of a rejection. |
| `pom.xml` | Pins `com.github.sviperll:result4j` 1.2.0. |

## How it works underneath

`Result.success(value)` and `Result.error(problem)` are the two ways to create one. Internally a `Result` is an object with a flag and one field. `map(f)` runs `f` only when the result is a success. `recoverError(g)` runs `g` only when it is an error and must return the success type, so after it you always have a plain value. `isError()` tells you which one you have.

Java methods that declare **checked exceptions** (exceptions the compiler forces you to catch, such as `IOException`) do not fit well with lambdas. The usual cure, used here, is one small method that catches the exception at the boundary and returns a `Result`.

## Walkthrough of this repository's use

Building the values, in the parser:

```java
  /** Parses {@code order_id,customer,type,amount}. The first rule that fails names the reason. */
  public static Result<Order, Rejection> parse(String line) {
    List<String> fields = Arrays.stream(line.split(",", -1)).map(String::strip).toList();
    return fields.size() != FIELDS
        ? reject(line, "malformed line: expected 4 fields")
        : build(line, fields);
  }
```

`parse` returns `reject(line, "malformed line: expected 4 fields")` for a line with the wrong number of fields and otherwise continues to `build`, which returns `Result.success(new Order(...))` or an error (`reject`, line 67, is `Result.error(new Rejection(line, reason))`).

```java

  private static Result<Order, Rejection> reject(String line, String reason) {
    return Result.error(new Rejection(line, reason));
  }
}
```

Branching on the value, in the flow:

```java
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

The router asks `Result::isError` (a method reference) of each message's payload and sends errors to the channel `invalid` and successes through `this::order` to the channel `valid` (the Spring Integration note explains routers and channels). Both branches carry the same kind of message until the router splits them.

Unwrapping the error side, in the dead-letter handler:

```java
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
```

`result.map(order -> (Rejection) null).recoverError(Function.identity())` turns the result into a `Rejection`: `map` changes the success side to a `null` placeholder of the right type, and `recoverError(Function.identity())` replaces an error by itself (the function that returns its argument) so that both sides now have the type `Rejection`. For an error the result is the rejection.

Unwrapping the success side:

```java
  private Order order(Result<Order, Rejection> result) {
    return result.map(Function.identity()).discardError().orElseThrow();
  }
}
```

`result.map(Function.identity()).discardError()` gives an `Optional<Order>`: `discardError()` keeps the success and drops the error, and `.orElseThrow()` unwraps it. It is safe because only successes reach this method.

In the legacy loader, a stream of parsed lines is reduced to the valid orders:

```java
  private List<Order> validOrders(Stream<String> lines) {
    return lines
        .skip(1)
        .map(OrderParser::parse)
        .map(parsed -> parsed.discardError())
        .flatMap(java.util.Optional::stream)
        .toList();
  }
```

`.map(OrderParser::parse)` gives a stream of results, `.map(parsed -> parsed.discardError())` turns each into an `Optional<Order>`, and `.flatMap(Optional::stream)` keeps only the present ones. Bad lines vanish, exactly as the old batch job dropped them, while the flow sends them to the dead-letter queue.

## Try it yourself

1. See the dependency: `grep -n -B1 -A2 result4j pom.xml`.
2. See the value flow in a test: `grep -n "Result\|recoverError" src/test/java/plainround/orderflow/domain/DomainTest.java`.
3. Read a rejection: run `./mvnw -q test -Dtest=DomainTest` and open the test `a_bad_line_is_refused_with_the_reason_the_dead_letter_queue_will_carry` in `DomainTest.java`.
4. Explore the library: `unzip -l ~/.m2/repository/com/github/sviperll/result4j/1.2.0/result4j-1.2.0.jar | head`.
5. Check the reasons in the dead-letter queue after `make apps partner`: open the RabbitMQ management page at `http://localhost:56722` (user and password from `.env`), queue `dead-letter`, "Get messages", and read the header `reason`.

## Mistakes people make

- **Treating `Result` as a new place for `null`.** A `Result.success(null)` is allowed but useless; keep values non-null.
- **Catching `Exception` everywhere.** Catch once, at the boundary of the library you call, and return a `Result`; the rest of the code stays free of `try`.
- **Forgetting that `recoverError` returns the success type.** After `.map(a).recoverError(b)` both branches must produce the same type.

## Check yourself

1. What two things can a `Result<Order, Rejection>` hold? *An order, or a rejection with the line and the reason.*
2. Why return a `Result` and not throw? *An invalid line is an expected outcome that the flow routes; it is not a bug.*
3. What does `discardError()` return? *An `Optional` that holds the success, or is empty for an error.*
4. How does the router decide? *`Result::isError` on the message payload.*
5. Why does the loader flatMap `Optional::stream`? *It keeps the present values and drops the empty ones.*

## Further reading

- Result4J on GitHub (README and API): https://github.com/sviperll/result4j
- Maven coordinates pinned in this repository: `com.github.sviperll:result4j:1.2.0`.
