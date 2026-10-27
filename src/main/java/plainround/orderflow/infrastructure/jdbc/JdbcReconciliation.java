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
}
