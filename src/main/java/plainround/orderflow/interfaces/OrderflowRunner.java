package plainround.orderflow.interfaces;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import plainround.orderflow.application.GeneratePartnerFile;
import plainround.orderflow.application.ReconcileOrders;
import plainround.orderflow.infrastructure.jdbc.LegacyBatchLoader;
import plainround.orderflow.infrastructure.sftp.SftpPartnerDrop;

/**
 * The tools: {@code --command=partner|legacy-load|reconcile|status|health|serve}. The long-running
 * services ({@code flow}, {@code order-service} profiles) use {@code serve}, which does nothing and
 * lets their threads keep the process alive.
 */
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

  @Override
  public void run(ApplicationArguments args) {
    Map<String, Function<ApplicationArguments, Integer>> commands =
        Map.of(
            "partner",
            this::partner,
            "legacy-load",
            this::legacyLoad,
            "reconcile",
            this::reconcile,
            "status",
            this::status,
            "health",
            this::health,
            "serve",
            arguments -> 0);
    exitCode = commands.getOrDefault(option(args, "command", "serve"), unknown()).apply(args);
  }

  @Override
  public int getExitCode() {
    return exitCode;
  }

  private int partner(ApplicationArguments args) {
    int night = Integer.parseInt(option(args, "night", "1"));
    Path file =
        Path.of(option(args, "out", "data"))
            .resolve(option(args, "name", "orders-" + night + ".csv"));
    file.getParent().toFile().mkdirs();
    int valid =
        generator.write(file, night, Long.parseLong(option(args, "seed", String.valueOf(night))));
    drop.upload(file);
    System.out.println(
        "uploaded " + file.getFileName() + " with " + valid + " valid distinct orders");
    return 0;
  }

  private int legacyLoad(ApplicationArguments args) {
    LegacyBatchLoader.Result result = legacy.loadNew();
    System.out.println(
        "legacy batch loaded " + result.files() + " file(s), " + result.orders() + " orders");
    return 0;
  }

  private int reconcile(ApplicationArguments args) {
    ReconcileOrders.Outcome outcome = reconcile.run();
    System.out.println(" only_in_batch | only_in_events | different | same");
    System.out.printf(
        "%14d | %14d | %9d | %5d%n",
        outcome.report().onlyInBatch(),
        outcome.report().onlyInEvents(),
        outcome.report().different(),
        outcome.report().same());
    outcome.differences().forEach(line -> System.out.println("  " + line));
    System.out.println(
        outcome.mayRetireLegacy()
            ? "legacy path may be retired"
            : "legacy path stays: not enough clean nights yet");
    return outcome.exitCode();
  }

  private int status(ApplicationArguments args) {
    System.out.println("sftp files: " + drop.list());
    List.of("retail", "wholesale", "dead-letter", "rejected").forEach(this::queue);
    System.out.println(
        "orders_batch="
            + count("orders_batch")
            + " orders_events="
            + count("orders_events")
            + " seen_orders="
            + jdbc.queryForObject(
                "SELECT count(*) FROM int_metadata_store WHERE region = 'orders'", Long.class));
    return 0;
  }

  private void queue(String name) {
    QueueInformation info = admin.getQueueInfo(name);
    System.out.printf(
        "queue %-12s %d messages%n", name, info == null ? -1 : info.getMessageCount());
  }

  private Long count(String table) {
    return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
  }

  private int health(ApplicationArguments args) {
    jdbc.queryForObject("SELECT 1", Integer.class);
    admin.getQueueInfo("retail");
    System.out.println(
        "ok database, broker and sftp folder reachable (" + drop.list().size() + " file(s))");
    return 0;
  }

  private Function<ApplicationArguments, Integer> unknown() {
    return args -> {
      System.err.println("unknown command");
      return 2;
    };
  }

  private static String option(ApplicationArguments args, String name, String fallback) {
    return args.containsOption(name) ? args.getOptionValues(name).getFirst() : fallback;
  }
}
