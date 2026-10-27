package plainround.orderflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import plainround.orderflow.application.ProcessOrderMessage.Outcome;
import plainround.orderflow.domain.Order;
import plainround.orderflow.domain.OrderParser;
import plainround.orderflow.domain.OrderType;
import plainround.orderflow.domain.ReconciliationReport;
import plainround.orderflow.domain.RetirementPolicy;

class ApplicationTest {

  @TempDir Path folder;

  private final Set<String> stored = new HashSet<>();
  private final Order order = new Order("O1", "C1", OrderType.RETAIL, new BigDecimal("10.00"));

  @Test
  void the_first_delivery_is_stored_and_a_repeated_one_is_a_duplicate_that_changes_nothing() {
    ProcessOrderMessage process = new ProcessOrderMessage(o -> stored.add(o.orderId()));
    assertThat(process.handle(order)).isEqualTo(Outcome.STORED);
    assertThat(process.handle(order)).isEqualTo(Outcome.DUPLICATE);
    assertThat(stored).containsExactly("O1");
  }

  private static class Fakes implements ReconciliationSource, ReconciliationHistory {
    ReconciliationReport next = new ReconciliationReport(0, 0, 0, 100);
    final List<ReconciliationReport> recorded = new ArrayList<>();

    @Override
    public ReconciliationReport compare() {
      return next;
    }

    @Override
    public List<String> differences(int limit) {
      return List.of("O7 different (batch 10.00/RECEIVED, events 11.00/RECEIVED)");
    }

    @Override
    public void record(ReconciliationReport report) {
      recorded.add(0, report);
    }

    @Override
    public List<ReconciliationReport> latest(int count) {
      return recorded.stream().limit(count).toList();
    }
  }

  @Test
  void a_clean_night_is_recorded_exits_zero_and_lists_no_differences() {
    Fakes fakes = new Fakes();
    ReconcileOrders.Outcome outcome =
        new ReconcileOrders(fakes, fakes, new RetirementPolicy(2)).run();
    assertThat(outcome.exitCode()).isZero();
    assertThat(outcome.differences()).isEmpty();
    assertThat(outcome.mayRetireLegacy()).isFalse();
    assertThat(fakes.recorded).hasSize(1);
  }

  @Test
  void
      enough_clean_nights_in_a_row_allow_retirement_and_a_dirty_night_lists_the_orders_and_exits_one() {
    Fakes fakes = new Fakes();
    ReconcileOrders reconcile = new ReconcileOrders(fakes, fakes, new RetirementPolicy(2));
    reconcile.run();
    assertThat(reconcile.run().mayRetireLegacy()).isTrue();
    fakes.next = new ReconciliationReport(0, 0, 1, 99);
    ReconcileOrders.Outcome dirty = reconcile.run();
    assertThat(dirty.exitCode()).isEqualTo(1);
    assertThat(dirty.differences()).hasSize(1);
    assertThat(dirty.mayRetireLegacy()).isFalse();
  }

  @Test
  void the_partner_file_has_the_lessons_shape() throws IOException {
    Path file = folder.resolve("orders-1.csv");
    int valid = new GeneratePartnerFile().write(file, 1, 1);
    List<String> lines = Files.readAllLines(file);
    assertThat(valid).isEqualTo(9997);
    assertThat(lines).hasSize(10_002);
    assertThat(lines.getFirst()).isEqualTo("order_id,customer,type,amount");
    assertThat(lines.getLast()).isEqualTo(lines.get(11));
    long bad = lines.stream().skip(1).filter(line -> OrderParser.parse(line).isError()).count();
    assertThat(bad).isEqualTo(3);
    assertThat(
            lines.stream()
                .skip(1)
                .distinct()
                .filter(line -> !OrderParser.parse(line).isError())
                .count())
        .isEqualTo(9997);
  }

  @Test
  void different_nights_use_different_order_ids() throws IOException {
    new GeneratePartnerFile().write(folder.resolve("a.csv"), 1, 1);
    new GeneratePartnerFile().write(folder.resolve("b.csv"), 2, 2);
    Set<String> first =
        Files.readAllLines(folder.resolve("a.csv")).stream()
            .skip(1)
            .map(l -> l.split(",")[0])
            .collect(java.util.stream.Collectors.toSet());
    Set<String> second =
        Files.readAllLines(folder.resolve("b.csv")).stream()
            .skip(1)
            .map(l -> l.split(",")[0])
            .collect(java.util.stream.Collectors.toSet());
    assertThat(first).doesNotContainAnyElementsOf(second);
  }
}
