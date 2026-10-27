package plainround.orderflow.infrastructure.jdbc;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.integration.sftp.session.SftpRemoteFileTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import plainround.orderflow.domain.Order;
import plainround.orderflow.domain.OrderParser;
import plainround.orderflow.infrastructure.sftp.SftpPartnerDrop;

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

  private List<Order> validOrders(Stream<String> lines) {
    return lines
        .skip(1)
        .map(OrderParser::parse)
        .map(parsed -> parsed.discardError())
        .flatMap(java.util.Optional::stream)
        .toList();
  }

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
