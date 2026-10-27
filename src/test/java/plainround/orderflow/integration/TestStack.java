package plainround.orderflow.integration;

import java.nio.file.Path;
import java.util.stream.Stream;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import plainround.orderflow.interfaces.OrderFlowApplication;

/**
 * Helpers for the tests that need the compose services: a database of its own ({@code
 * orderflow_test}) with the same SQL as {@code make db}, and application contexts that start the
 * flow and the order service inside the test's JVM.
 */
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
}
