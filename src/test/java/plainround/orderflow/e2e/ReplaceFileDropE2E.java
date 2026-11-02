package plainround.orderflow.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.context.ConfigurableApplicationContext;
import plainround.orderflow.application.GeneratePartnerFile;
import plainround.orderflow.application.ReconcileOrders;
import plainround.orderflow.infrastructure.flow.FlowCounters;
import plainround.orderflow.infrastructure.jdbc.LegacyBatchLoader;
import plainround.orderflow.infrastructure.sftp.SftpPartnerDrop;
import plainround.orderflow.integration.TestStack;

/**
 * The lesson's scenario, in order, against the real SFTP server, broker and database, with the flow
 * and the order service running inside the test's JVM: one file through the flow, a resend, a
 * restart of the flow, the order service draining the queues, the legacy batch and the
 * reconciliation, the planted difference, and a flow stopped in the middle of a file.
 */
@Tag("e2e")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReplaceFileDropE2E {

  private static final Duration LONG = Duration.ofMinutes(6);

  /**
   * A resent file: 9,997 valid orders already published, the one line the file repeats, and the 3
   * bad lines already dead-lettered.
   */
  private static final int RESENT_FILE_DUPLICATES = 9997 + 1 + 3;

  @TempDir static Path folder;

  private static Path inbox;

  @BeforeAll
  static void clean() {
    inbox = folder.resolve("inbox");
    TestStack.resetDatabase();
    try (ConfigurableApplicationContext context = TestStack.start(folder.resolve("cleaner"))) {
      context.getBean(SftpPartnerDrop.class).clear();
      TestStack.purgeQueues(context.getBean(RabbitAdmin.class));
    }
  }

  private void upload(ConfigurableApplicationContext context, int night, String name)
      throws Exception {
    Path file = folder.resolve(name);
    new GeneratePartnerFile().write(file, night, night);
    context.getBean(SftpPartnerDrop.class).upload(file);
  }

  private int published(RabbitAdmin admin) {
    return TestStack.depth(admin, "retail") + TestStack.depth(admin, "wholesale");
  }

  @Test
  @Order(1)
  void a_file_becomes_9997_messages_and_the_3_bad_lines_wait_in_the_dead_letter_queue()
      throws Exception {
    try (ConfigurableApplicationContext flow = TestStack.start(inbox, "flow")) {
      RabbitAdmin admin = flow.getBean(RabbitAdmin.class);
      upload(flow, 1, "orders-1.csv");
      Awaitility.await()
          .atMost(LONG)
          .pollInterval(Duration.ofSeconds(2))
          .until(() -> TestStack.depth(admin, "dead-letter") == 3);
      Awaitility.await().atMost(LONG).until(() -> published(admin) == 9997);
      assertThat(TestStack.depth(admin, "retail") + TestStack.depth(admin, "wholesale"))
          .isEqualTo(9997);
    }
  }

  @Test
  @Order(2)
  void the_same_file_under_a_new_name_adds_no_order_and_the_dead_letter_queue_does_not_grow()
      throws Exception {
    try (ConfigurableApplicationContext flow = TestStack.start(inbox, "flow")) {
      RabbitAdmin admin = flow.getBean(RabbitAdmin.class);
      FlowCounters counters = flow.getBean(FlowCounters.class);
      upload(flow, 1, "orders-1-again.csv");
      Awaitility.await()
          .atMost(LONG)
          .until(
              () ->
                  counters.duplicateCount() == RESENT_FILE_DUPLICATES
                      && counters.publishedCount() == 0);
      assertThat(published(admin)).isEqualTo(9997);
      assertThat(TestStack.depth(admin, "dead-letter")).isEqualTo(3);
    }
  }

  @Test
  @Order(3)
  void the_flow_remembers_what_it_saw_across_a_restart() throws Exception {
    try (ConfigurableApplicationContext flow = TestStack.start(inbox, "flow")) {
      RabbitAdmin admin = flow.getBean(RabbitAdmin.class);
      FlowCounters counters = flow.getBean(FlowCounters.class);
      upload(flow, 1, "orders-1-third.csv");
      Awaitility.await()
          .atMost(LONG)
          .until(
              () ->
                  counters.duplicateCount() == RESENT_FILE_DUPLICATES
                      && counters.publishedCount() == 0);
      assertThat(published(admin)).isEqualTo(9997);
      assertThat(TestStack.depth(admin, "dead-letter")).isEqualTo(3);
    }
  }

  @Test
  @Order(4)
  void the_order_service_drains_the_queues_into_the_new_table_exactly_once() {
    try (ConfigurableApplicationContext service = TestStack.start(inbox, "order-service")) {
      RabbitAdmin admin = service.getBean(RabbitAdmin.class);
      Awaitility.await().atMost(LONG).until(() -> published(admin) == 0);
      Awaitility.await()
          .atMost(LONG)
          .until(
              () ->
                  TestStack.jdbc().queryForObject("SELECT count(*) FROM orders_events", Long.class)
                      == 9997);
      assertThat(
              TestStack.jdbc()
                  .queryForObject("SELECT count(DISTINCT order_id) FROM orders_events", Long.class))
          .isEqualTo(9997);
    }
  }

  @Test
  @Order(5)
  void the_legacy_batch_and_the_new_path_reconcile_with_no_difference_and_a_planted_one_is_found() {
    try (ConfigurableApplicationContext tools = TestStack.start(inbox)) {
      LegacyBatchLoader.Result loaded = tools.getBean(LegacyBatchLoader.class).loadNew();
      assertThat(loaded.files()).isEqualTo(3);
      assertThat(TestStack.jdbc().queryForObject("SELECT count(*) FROM orders_batch", Long.class))
          .isEqualTo(9997);
      ReconcileOrders.Outcome clean = tools.getBean(ReconcileOrders.class).run();
      assertThat(clean.report())
          .hasToString(
              "ReconciliationReport[onlyInBatch=0, onlyInEvents=0, different=0, same=9997]");
      TestStack.jdbc()
          .update("UPDATE orders_events SET amount = amount + 1 WHERE order_id = 'N01O000042'");
      ReconcileOrders.Outcome dirty = tools.getBean(ReconcileOrders.class).run();
      assertThat(dirty.report().different()).isEqualTo(1);
      assertThat(dirty.differences()).singleElement().asString().startsWith("N01O000042 different");
      assertThat(dirty.exitCode()).isEqualTo(1);
    }
  }

  @Test
  @Order(6)
  void
      a_flow_stopped_in_the_middle_of_a_file_and_started_again_loses_no_order_and_creates_no_duplicate_row()
          throws Exception {
    TestStack.resetDatabase();
    Path second = folder.resolve("second");
    try (ConfigurableApplicationContext tools = TestStack.start(second)) {
      tools.getBean(SftpPartnerDrop.class).clear();
      TestStack.purgeQueues(tools.getBean(RabbitAdmin.class));
    }
    try (ConfigurableApplicationContext flow = TestStack.start(second, "flow")) {
      RabbitAdmin admin = flow.getBean(RabbitAdmin.class);
      upload(flow, 2, "orders-2.csv");
      Awaitility.await()
          .atMost(LONG)
          .pollInterval(Duration.ofMillis(200))
          .until(() -> published(admin) > 1500);
    }
    assertThat(
            TestStack.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM int_metadata_store WHERE region = 'orders'", Long.class))
        .isLessThan(9997);
    try (ConfigurableApplicationContext flow = TestStack.start(second, "flow", "order-service")) {
      RabbitAdmin admin = flow.getBean(RabbitAdmin.class);
      Awaitility.await()
          .atMost(LONG)
          .until(
              () ->
                  TestStack.jdbc().queryForObject("SELECT count(*) FROM orders_events", Long.class)
                      == 9997);
      Awaitility.await().atMost(LONG).until(() -> published(admin) == 0);
      assertThat(
              TestStack.jdbc()
                  .queryForObject("SELECT count(DISTINCT order_id) FROM orders_events", Long.class))
          .isEqualTo(9997);
    }
  }
}
