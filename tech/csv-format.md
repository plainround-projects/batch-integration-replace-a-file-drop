# CSV (the partner's file format)

## What it is

**CSV** (comma-separated values) is a plain-text format for a table. Each line is a row; the values of a row are separated by commas; the first line is usually a **header** that names the columns. It is the simplest format two companies can agree on, and every spreadsheet and database can read it. There is no single official rule for the details (quotes around values that contain commas, which line ending to use, which character set); the partner and the receiver must agree. This repository's partner file is the simple kind: no quoting, no commas inside values, UTF-8 text, one order per line.

## Why this repository uses it

The lesson replaces a *nightly file drop*, and the partner's file is the input that cannot change. Every other technology in the repository (the SFTP adapter, the splitter, the parser, the dead-letter queue) exists to handle this one file format correctly, including its ugly cases: a header line, an empty amount, a negative amount, an unknown type, a line repeated, and a file sent twice.

## Where it appears

| Path | What it does with CSV |
| --- | --- |
| `src/main/java/plainround/orderflow/application/GeneratePartnerFile.java` | Writes the pretend partner's file. |
| `src/main/java/plainround/orderflow/domain/OrderParser.java` | Reads one line: four fields, in a fixed order. |
| `src/main/java/plainround/orderflow/infrastructure/flow/OrderFlowConfiguration.java` | Splits a file into lines and drops the header. |
| `src/main/java/plainround/orderflow/infrastructure/jdbc/LegacyBatchLoader.java` | The stand-in for the old job: skips the header and parses the same lines. |
| `src/test/java/plainround/orderflow/domain/DomainTest.java` | Parameterized tests with good and bad lines. |

## How it works underneath

A CSV reader does three things. It splits the text into **lines** at the line ending. It splits each line into **fields** at the commas. And it gives each field a meaning by its **position** (the first field is the order id, the second the customer, the third the type, the fourth the amount): CSV itself carries no types, so everything is text until the receiver converts it. That is why parsing is a separate, testable step.

Two details matter here. First, `"a,b,".split(",")` in Java drops the trailing empty field and returns two pieces, so the parser uses `split(",", -1)`, where the negative limit keeps trailing empty fields; otherwise a line whose amount is empty would look like it has three fields instead of four fields with an empty last one (`malformed` instead of `amount missing`). Second, the header is just another line: the receiver must recognize and skip it.

## Walkthrough of this repository's use

The file has this shape, the header and then one order per line (three examples from the generator):

```csv
order_id,customer,type,amount
N01O000001,C0187,wholesale,431.27
N01O000002,C0041,retail,92.80
N01O000003,C0412,retail,15.04
```

The order id carries the night (`N01`) and a number (`O000001`), so a week of files is a week of distinct orders. The generator writes it:

```java
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

  private String order(int night, int number, Random random) {
    String type = random.nextBoolean() ? "retail" : "wholesale";
    BigDecimal amount =
        BigDecimal.valueOf(5 + random.nextDouble() * 895).setScale(2, RoundingMode.HALF_UP);
    return "N%02dO%06d,C%04d,%s,%s".formatted(night, number, 1 + random.nextInt(500), type, amount);
  }
```

`lines.get(0)` is the header `order_id,customer,type,amount`; every other line is built by `"N%02dO%06d,C%04d,%s,%s".formatted(...)`. The ugly cases are planted on purpose:

```java
  private void plantBadLines(List<String> lines, int night) {
    lines.set(500, "N%02dX999001,C0001,retail,".formatted(night));
    lines.set(2500, "N%02dX999002,C0002,retail,-4".formatted(night));
    lines.set(7500, "N%02dX999003,C0003,barter,10".formatted(night));
  }
```

Line 500 has an empty amount (`N01X999001,C0001,retail,`), line 2500 a negative amount, line 7500 an unknown type (`barter`). The generator also adds a copy of line 11 at the end, so the file has 10,000 order lines, 3 bad, and 1 repeated, which is 9,997 valid distinct orders (`ORDERS - 3`). The reader drops the header in the flow:

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
```

`!line.startsWith("order_id")` is the header test. And each line is parsed by position:

```java
  /** Parses {@code order_id,customer,type,amount}. The first rule that fails names the reason. */
  public static Result<Order, Rejection> parse(String line) {
    List<String> fields = Arrays.stream(line.split(",", -1)).map(String::strip).toList();
    return fields.size() != FIELDS
        ? reject(line, "malformed line: expected 4 fields")
        : build(line, fields);
  }
```

The tests list the cases, one per line of a table:

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
```

`@CsvSource` is JUnit's own use of CSV: each string is a row, `delimiter = '|'` makes the bar the separator (because the test data itself contains commas), and the two columns are the input line and the expected reason.

## Try it yourself

1. Look at a file: `make partner` then, to read the local copy, generate one without uploading by running the unit test `./mvnw -q test -Dtest=ApplicationTest#the_partner_file_has_the_lessons_shape`, or read the file on the server: `docker compose exec -T sftp head -3 /home/partner/upload/orders-1.csv`.
2. Count the lines: `docker compose exec -T sftp wc -l /home/partner/upload/orders-1.csv` prints 10002 (header, 10,000 orders and the repeated line).
3. Find the planted bad lines: `docker compose exec -T sftp sed -n '501p;2501p;7501p' /home/partner/upload/orders-1.csv` (line numbers are one higher because of the header).
4. Find the repeated line: `docker compose exec -T sftp sh -c "sed -n '12p' /home/partner/upload/orders-1.csv; tail -1 /home/partner/upload/orders-1.csv"` prints the same text twice.
5. See Java's `split` pitfall in `jshell`: `"a,b,".split(",").length` is 2, and `"a,b,".split(",", -1).length` is 3.

## Mistakes people make

- **Splitting on commas when values can contain commas.** Real CSV quotes such values; this partner's file does not need it, and the parser would refuse such a line as having too many fields.
- **Forgetting the header.** It is not an order.
- **Forgetting trailing empty fields.** See `split(",", -1)` above.
- **Reading numbers as floating point.** The amount is `BigDecimal`.
- **Assuming a file is sent once.** Partners resend; the design makes a resend harmless.

## Check yourself

1. What does the first line of the partner's file contain? *The header `order_id,customer,type,amount`.*
2. Why is `split(",", -1)` used? *To keep trailing empty fields so a missing amount is reported as such.*
3. How many valid distinct orders does a generated file hold? *9,997.*
4. What does the order id `N03O000042` mean? *Night 3, order 42.*
5. Where does the meaning of a column come from in CSV? *From its position, agreed by the two sides.*

## Further reading

- RFC 4180, the common description of CSV: https://datatracker.ietf.org/doc/html/rfc4180
- `String.split` in the Java 21 API: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/String.html#split(java.lang.String,int)
