package plainround.orderflow.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import plainround.orderflow.domain.Order;
import plainround.orderflow.domain.OrderType;
import plainround.orderflow.domain.ReconciliationReport;
import plainround.orderflow.infrastructure.amqp.ConfirmedPublisher;
import plainround.orderflow.infrastructure.jdbc.JdbcOrderStore;
import plainround.orderflow.infrastructure.jdbc.JdbcReconciliation;

/** The adapters against the real database and the real broker. */
@Tag("integration")
class StoresAndBrokerIT {

  @TempDir Path inbox;
  private JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    TestStack.resetDatabase();
    jdbc = TestStack.jdbc();
  }

  private Order order(String id, String amount) {
    return new Order(id, "C1", OrderType.RETAIL, new BigDecimal(amount));
  }

  @Test
  void the_primary_key_makes_a_second_delivery_of_an_order_a_no_op() {
    JdbcOrderStore store = new JdbcOrderStore(jdbc);
    assertThat(store.saveIfAbsent(order("O1", "10.00"))).isTrue();
    assertThat(store.saveIfAbsent(order("O1", "99.00"))).isFalse();
    assertThat(
            jdbc.queryForObject(
                "SELECT amount FROM orders_events WHERE order_id = 'O1'", BigDecimal.class))
        .isEqualByComparingTo("10.00");
  }

  @Test
  void the_reconciliation_counts_each_case_in_one_pass_and_names_the_orders() {
    jdbc.update(
        "INSERT INTO orders_batch (order_id, customer, type, amount) VALUES ('A','C','retail',1),('B','C','retail',2),('C','C','retail',3),('D','C','retail',4)");
    jdbc.update(
        "INSERT INTO orders_events (order_id, customer, type, amount) VALUES ('B','C','retail',2),('C','C','retail',33),('D','C','retail',4),('E','C','retail',5)");
    JdbcReconciliation reconciliation = new JdbcReconciliation(jdbc);
    assertThat(reconciliation.compare()).isEqualTo(new ReconciliationReport(1, 1, 1, 2));
    assertThat(reconciliation.differences(10))
        .hasSize(3)
        .anyMatch(line -> line.startsWith("A only in batch"))
        .anyMatch(line -> line.startsWith("C different"))
        .anyMatch(line -> line.startsWith("E only in events"));
  }

  @Test
  void a_status_difference_counts_as_different_too() {
    jdbc.update(
        "INSERT INTO orders_batch (order_id, customer, type, amount, status) VALUES ('A','C','retail',1,'RECEIVED')");
    jdbc.update(
        "INSERT INTO orders_events (order_id, customer, type, amount, status) VALUES ('A','C','retail',1,'SHIPPED')");
    assertThat(new JdbcReconciliation(jdbc).compare().different()).isEqualTo(1);
  }

  @Test
  void the_history_keeps_the_latest_reports_newest_first() {
    JdbcReconciliation reconciliation = new JdbcReconciliation(jdbc);
    reconciliation.record(new ReconciliationReport(0, 0, 0, 10));
    reconciliation.record(new ReconciliationReport(0, 0, 1, 9));
    assertThat(reconciliation.latest(5))
        .containsExactly(
            new ReconciliationReport(0, 0, 1, 9), new ReconciliationReport(0, 0, 0, 10));
  }

  @Test
  void
      a_confirmed_publish_leaves_a_persistent_message_in_the_queue_of_its_type_and_a_dead_letter_carries_its_reason() {
    try (ConfigurableApplicationContext context = TestStack.start(inbox)) {
      RabbitAdmin admin = context.getBean(RabbitAdmin.class);
      TestStack.purgeQueues(admin);
      ConfirmedPublisher publisher =
          new ConfirmedPublisher(context.getBean(RabbitTemplate.class), 5000);
      publisher.publishOrder(order("O1", "10.00"), "orders-1.csv");
      publisher.publishDeadLetter("O2,C,retail,", "amount missing", "orders-1.csv");
      assertThat(TestStack.depth(admin, "retail")).isEqualTo(1);
      assertThat(TestStack.depth(admin, "wholesale")).isZero();
      Message dead = context.getBean(RabbitTemplate.class).receive("dead-letter", 5000);
      assertThat(dead).isNotNull();
      assertThat(dead.getMessageProperties().getHeaders())
          .containsEntry("reason", "amount missing");
      TestStack.purgeQueues(admin);
    }
  }

  @Test
  void
      a_message_that_cannot_be_read_is_rejected_to_the_rejected_queue_and_the_service_keeps_working() {
    try (ConfigurableApplicationContext context = TestStack.start(inbox, "order-service")) {
      RabbitAdmin admin = context.getBean(RabbitAdmin.class);
      RabbitTemplate template = context.getBean(RabbitTemplate.class);
      TestStack.purgeQueues(admin);
      template.send(
          "",
          "retail",
          MessageBuilder.withBody("{not json".getBytes())
              .setContentType("application/json")
              .build());
      Awaitility.await()
          .atMost(Duration.ofSeconds(60))
          .until(() -> TestStack.depth(admin, "rejected") == 1);
      new ConfirmedPublisher(template, 5000).publishOrder(order("O9", "5.00"), "x.csv");
      Awaitility.await()
          .atMost(Duration.ofSeconds(60))
          .until(() -> jdbc.queryForObject("SELECT count(*) FROM orders_events", Long.class) == 1);
      assertThat(TestStack.depth(admin, "retail")).isZero();
      TestStack.purgeQueues(admin);
    }
  }
}
