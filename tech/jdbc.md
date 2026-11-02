# JDBC and Spring's JdbcTemplate

## What it is

A **database** is a program that stores data and answers questions about it. **PostgreSQL** is one such program. To talk to it from Java you use **JDBC** (Java Database Connectivity): a standard set of Java interfaces for sending SQL text to a database and reading back the rows. The code that makes JDBC work with one specific database is a **driver** (here the PostgreSQL driver, `org.postgresql:postgresql`, which speaks PostgreSQL's network protocol).

Three terms: a **connection** is an open conversation with the database; opening one is slow (a network handshake and a login), so programs keep a **connection pool**, a set of already-open connections that code borrows and returns (here the pool is **HikariCP**, which Spring Boot configures for you). A **transaction** groups several changes so that they all happen or none does. A **prepared statement** is SQL with `?` placeholders; you pass the values separately, which is both faster and safe against SQL injection (an attack that hides SQL in a value).

Spring's **`JdbcTemplate`** wraps JDBC so you do not write the same repetitive code for every query: it borrows a connection, prepares the statement, runs it, maps rows, closes everything and returns the connection to the pool, even when something fails.

## Why this repository uses it

Three places write to or read from PostgreSQL: the order service stores each delivered order, the reconciliation compares two tables, and the stand-in for the old batch job loads a file into a third. All three are small, explicit SQL statements, so plain `JdbcTemplate` is clearer than an object-relational mapper, and every statement can be read next to the SQL file that defines its tables.

## Where it appears

| Path | What it does with JDBC |
| --- | --- |
| `${J}/infrastructure/jdbc/JdbcOrderStore.java` | The idempotent insert of the order service. |
| `${J}/infrastructure/jdbc/JdbcReconciliation.java` | The comparison query, the detail query, and the history table. |
| `${J}/infrastructure/jdbc/LegacyBatchLoader.java` | The legacy loader: one transaction per file, batched inserts. |
| `${J}/interfaces/OrderflowRunner.java` | The `status` and `health` counts. |
| `src/main/resources/application.yml` | The datasource and connection pool settings. |
| `src/test/java/plainround/orderflow/integration/TestStack.java` | A separate database for the tests, built from the same SQL files. |

## How it works underneath

A `jdbc:postgresql://host:port/database` **URL** tells the driver where the database is. When code calls `jdbcTemplate.queryForObject(sql, Long.class, args...)`, `JdbcTemplate` asks the pool for a connection, sends the SQL with the arguments as parameters, reads the single row, converts the first column to a `Long`, and returns the connection to the pool. If the pool has no free connection the call **waits** up to `connection-timeout` milliseconds and then fails with an error; that waiting is the "pool exhausted" problem that the lesson's step 4 shows.

A **cursor** reader (like `JdbcCursorItemReader`) keeps one connection busy for the whole read: the database remembers where the result set is and sends rows in groups of `fetchSize`. The connection is not returned to the pool until the reader is closed. So a reader holds one pooled connection while its worker also needs another one to write.

## Walkthrough of this repository's use

### `JdbcOrderStore.java`: the idempotent consumer

```java
package plainround.orderflow.infrastructure.jdbc;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import plainround.orderflow.application.OrderStore;
import plainround.orderflow.domain.Order;

/**
 * The new path's table. The primary key is the idempotent consumer: a second delivery inserts
 * nothing.
 */
@RequiredArgsConstructor
public class JdbcOrderStore implements OrderStore {

  private final JdbcTemplate jdbc;

  @Override
  public boolean saveIfAbsent(Order order) {
    return jdbc.update(
            "INSERT INTO orders_events (order_id, customer, type, amount) VALUES (?, ?, ?, ?) "
                + "ON CONFLICT (order_id) DO NOTHING",
            order.orderId(),
            order.customer(),
            order.type().queue(),
            order.amount())
        == 1;
  }
}
```

`jdbc.update(sql, args...)` runs an INSERT, UPDATE or DELETE and returns the number of rows changed. The statement has four `?` placeholders, filled in order by the four arguments (customer text, the type's queue name, the amount as `BigDecimal`); using placeholders instead of pasting values into the text prevents SQL injection and lets the database reuse the plan. `ON CONFLICT (order_id) DO NOTHING` says: if a row with this order id already exists, do nothing (no error). So the update returns 1 for a new order and 0 for a repeated delivery, and `== 1` turns that into the boolean the port promises. The primary key on `order_id` is the **idempotent consumer**: the broker may deliver an order twice, and the second delivery changes nothing.

### `JdbcReconciliation.java`: queries with results

```java
package plainround.orderflow.infrastructure.jdbc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import plainround.orderflow.application.ReconciliationHistory;
import plainround.orderflow.application.ReconciliationSource;
import plainround.orderflow.domain.ReconciliationReport;

/**
 * The comparison of the two paths, from {@code sql/reconcile.sql}, and the history of its results.
 */
@RequiredArgsConstructor
public class JdbcReconciliation implements ReconciliationSource, ReconciliationHistory {

  private final JdbcTemplate jdbc;

  @Override
  public ReconciliationReport compare() {
    return jdbc.queryForObject(
        sql("sql/reconcile.sql"),
        (rs, i) ->
            new ReconciliationReport(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)));
  }
```

`ReconciliationSource` and `ReconciliationHistory` are both implemented by this one class. `compare` runs `queryForObject(sql, rowMapper)`: a query that must return exactly one row, with a lambda `(rs, i) -> new ReconciliationReport(rs.getLong(1), ...)` that turns the row (`rs` is the `ResultSet`, `i` the row number) into the report; `getLong(1)` reads the first column. The SQL text comes from `sql/reconcile.sql` through the private `sql` method (end of the file), which reads the file from the classpath (the Maven note shows how the build copies it into the jar).

```java
  @Override
  public List<String> differences(int limit) {
    return jdbc.query(
        sql("sql/reconcile_details.sql"),
        (rs, i) ->
            "%s %s (batch %s/%s, events %s/%s)"
                .formatted(
                    rs.getString("order_id"),
                    rs.getString("kind"),
                    rs.getBigDecimal("batch_amount"),
                    rs.getString("batch_status"),
                    rs.getBigDecimal("events_amount"),
                    rs.getString("events_status")),
        limit);
  }
```

`differences(limit)` runs `jdbc.query(sql, rowMapper, limit)`: the third argument fills the single `?` at the end of `reconcile_details.sql` (`LIMIT ?`). The mapper builds one line per difference with `"%s %s (batch %s/%s, events %s/%s)".formatted(...)`, reading columns by name this time (`rs.getString("order_id")`).

```java
  @Override
  public void record(ReconciliationReport report) {
    jdbc.update(
        "INSERT INTO reconciliation_run (only_in_batch, only_in_events, different, same) VALUES (?, ?, ?, ?)",
        report.onlyInBatch(),
        report.onlyInEvents(),
        report.different(),
        report.same());
  }

  @Override
  public List<ReconciliationReport> latest(int count) {
    return jdbc.query(
        "SELECT only_in_batch, only_in_events, different, same FROM reconciliation_run ORDER BY run_id DESC LIMIT ?",
        (rs, i) ->
            new ReconciliationReport(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)),
        count);
  }

  private String sql(String path) {
    try {
      return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    } catch (IOException problem) {
      throw new UncheckedIOException(problem);
    }
  }
```

`record` inserts one row into `reconciliation_run`. `latest(count)` selects the newest rows (`ORDER BY run_id DESC LIMIT ?`). `sql(path)` loads a file with Spring's `ClassPathResource` (a file inside the jar) as UTF-8 text and turns the checked `IOException` into an unchecked one.

### `LegacyBatchLoader.java`: a transaction per file

A **transaction** is a group of statements that succeed or fail together. The old batch job loaded a whole file or nothing, and so does this stand-in.

```java
/**
 * The stand-in for the legacy nightly batch job: it fetches the files it has not loaded yet from
 * the same SFTP folder, keeps the valid lines and loads them into {@code orders_batch}, one
 * transaction per file, the next morning. It never touches the table the new path writes.
 */
@Slf4j
@RequiredArgsConstructor
public class LegacyBatchLoader {

  /** What a run of the loader did. */
  public record Result(int files, int orders) {}

  private final SftpRemoteFileTemplate sftp;
  private final SftpPartnerDrop folder;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;
  private final String remoteDirectory;

  /**
   * Loads every file not yet recorded in {@code legacy_loaded_file}; returns the files and orders
   * loaded.
   */
  public Result loadNew() {
    List<String> pending = folder.list().stream().filter(name -> !loaded(name)).toList();
    int orders = pending.stream().mapToInt(this::loadFile).sum();
    return new Result(pending.size(), orders);
  }
```

The Javadoc says what it stands for. `Result` is a small nested record of how many files and orders were loaded. The fields are the SFTP template, the folder adapter, the `JdbcTemplate`, a `TransactionTemplate` and the remote folder name. `loadNew` lists the files in the partner's folder, keeps those not yet recorded in `legacy_loaded_file` (`filter(name -> !loaded(name))`), loads each (`mapToInt(this::loadFile).sum()` adds up the orders of all files) and returns the counts.

```java
  private boolean loaded(String name) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM legacy_loaded_file WHERE file_name = ?)",
            Boolean.class,
            name));
  }

  private int loadFile(String name) {
    List<Order> orders = new java.util.ArrayList<>();
    sftp.get(remoteDirectory + "/" + name, stream -> orders.addAll(read(stream)));
    transaction.executeWithoutResult(status -> insert(name, orders));
    log.info("legacy batch loaded {} orders from {}", orders.size(), name);
    return orders.size();
  }

  private List<Order> read(java.io.InputStream stream) throws java.io.IOException {
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
      return validOrders(reader.lines());
    }
  }
```

`loaded(name)` asks `SELECT EXISTS (SELECT 1 FROM legacy_loaded_file WHERE file_name = ?)`, which returns a boolean (`Boolean.TRUE.equals(...)` is a null-safe test). `loadFile` downloads the file with `sftp.get(path, callback)`: the callback receives the open stream and parses it into a list; then `transaction.executeWithoutResult(status -> insert(name, orders))` runs the insert inside one transaction, which commits when the lambda ends and rolls back if it throws. `read` wraps the stream in a `BufferedReader` with `try (...)` so it is closed even on an error, and `validOrders` keeps the valid lines (skipping the header with `skip(1)`).

```java
  private void insert(String name, List<Order> orders) {
    jdbc.batchUpdate(
        "INSERT INTO orders_batch (order_id, customer, type, amount) VALUES (?, ?, ?, ?) ON CONFLICT (order_id) DO NOTHING",
        orders,
        1000,
        (statement, order) -> {
          statement.setString(1, order.orderId());
          statement.setString(2, order.customer());
          statement.setString(3, order.type().queue());
          statement.setBigDecimal(4, order.amount());
        });
    jdbc.update(
        "INSERT INTO legacy_loaded_file (file_name, orders) VALUES (?, ?)", name, orders.size());
  }
}
```

`insert` uses `jdbc.batchUpdate(sql, orders, 1000, setter)`: the same INSERT is sent in batches of up to 1,000 rows, and the lambda `(statement, order) -> {...}` fills the four placeholders of each row (`statement.setString(1, ...)` sets the first `?`). `ON CONFLICT (order_id) DO NOTHING` skips ids already present (the partner file contains one repeated line). Then one row in `legacy_loaded_file` records that the file is done, in the same transaction, so a crash between the two cannot leave a file half-recorded.

### The connection pool

```yaml
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:55561/orderflow}
    username: ${DB_USER:orderflow}
    password: ${DB_PASSWORD:}
    hikari:
      maximum-pool-size: 5
      connection-timeout: 20000
```

`spring.datasource.url` and the credentials come from environment variables with defaults. `hikari` is the pool (HikariCP): `maximum-pool-size: 5` keeps at most five open connections and `connection-timeout: 20000` waits up to 20 seconds for a free one before failing.

### The test database

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

The test helper reads connection details from environment variables with defaults, builds the URL of a database called `orderflow_test` (so tests never touch the data the demos use), and gives out a `JdbcTemplate` over a `DriverManagerDataSource` (a simple data source that opens a new connection each time: fine for tests). `resetDatabase` truncates the order tables and deletes the receiver's memory.

```java
  private static void createDatabase() {
    JdbcTemplate admin =
        new JdbcTemplate(
            new DriverManagerDataSource(
                "jdbc:postgresql://%s:%s/postgres".formatted(DB_HOST, DB_PORT),
                DB_USER,
                DB_PASSWORD));
    if (admin.queryForList("SELECT 1 FROM pg_database WHERE datname = ?", NAME).isEmpty()) {
      admin.execute("CREATE DATABASE " + NAME);
    }
    ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
    Stream.of("sql/001_schema.sql", "sql/migrations/001_reconciliation_history.sql")
        .map(file -> new FileSystemResource(Path.of(file)))
        .forEach(populator::addScript);
    populator.execute(
        new DriverManagerDataSource(
            "jdbc:postgresql://%s:%s/%s".formatted(DB_HOST, DB_PORT, NAME), DB_USER, DB_PASSWORD));
  }
```

`createDatabase` connects to the maintenance database `postgres`, creates `orderflow_test` if it does not exist, and runs the same two SQL files as `make db` through a `ResourceDatabasePopulator`.

## Try it yourself

1. Start the database: `make up db`.
2. Open a SQL prompt: `docker compose exec postgres psql -U orderflow -d orderflow`, then `\d orders_events` to see the table, `\q` to leave.
3. Try the idempotent insert by hand: `insert into orders_events (order_id, customer, type, amount) values ('X1','C1','retail',10) on conflict (order_id) do nothing;` prints `INSERT 0 1` the first time and `INSERT 0 0` the second.
4. See the pool in action: `make status` and read the counts it prints.
5. Run the adapter tests: `make test-integration` and read `StoresAndBrokerIT`.

## Mistakes people make

- **Building SQL by joining strings with user values.** That is SQL injection. Always pass values as `?` parameters, as this repository does.
- **Leaking connections.** Borrowed and never returned connections empty the pool. Use `JdbcTemplate` (or a try-with-resources block) so they are always returned.
- **A pool smaller than the number of threads that hold connections.** Callers wait on each other; size the pool for the largest number of threads that use it at once.
- **Reading a whole huge table into memory.** Use a cursor with a `fetchSize`, as the readers here do.
- **`null` from `queryForObject`.** A query such as `SELECT MAX(id)` over an empty table returns SQL `NULL`; the Java side gets `null`, so the code checks for it.

## Check yourself

1. Why placeholders and not string concatenation? *They prevent SQL injection and let the database reuse the plan.*
2. What does `update` return for the order insert? *1 for a new order, 0 when the id already existed.*
3. What does `transaction.executeWithoutResult` do? *Runs the lambda in one transaction: commit on success, rollback on an exception.*
4. Why is the file recorded in the same transaction as its orders? *So a crash cannot leave orders loaded but the file unrecorded, or the other way round.*
5. Why `batchUpdate` with 1000? *It sends rows in groups instead of one round trip per row.*

## Further reading

- Spring Framework data access with JDBC (JdbcTemplate): https://docs.spring.io/spring-framework/reference/data-access/jdbc.html
- PostgreSQL JDBC driver documentation: https://jdbc.postgresql.org/documentation/
- HikariCP configuration: https://github.com/brettwooldridge/HikariCP#gear-configuration-knobs-baby
