package plainround.orderflow.infrastructure.flow;

import com.github.sviperll.result4j.Result;
import java.time.Duration;
import java.util.function.Function;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.sftp.client.SftpClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.Pollers;
import org.springframework.integration.file.dsl.Files;
import org.springframework.integration.file.remote.session.SessionFactory;
import org.springframework.integration.file.splitter.FileSplitter;
import org.springframework.integration.jdbc.metadata.JdbcMetadataStore;
import org.springframework.integration.metadata.ConcurrentMetadataStore;
import org.springframework.integration.sftp.dsl.Sftp;
import org.springframework.integration.sftp.filters.SftpPersistentAcceptOnceFileListFilter;
import plainround.orderflow.domain.Order;
import plainround.orderflow.domain.OrderParser;
import plainround.orderflow.domain.Rejection;
import plainround.orderflow.infrastructure.OrderflowProperties;
import plainround.orderflow.infrastructure.amqp.ConfirmedPublisher;

/**
 * The partner's file as a message flow (active in the {@code flow} profile).
 *
 * <pre>
 * SFTP inbound adapter -> Splitter -> (header filter) -> Translator -> Router --invalid--> Dead Letter Channel
 *                                                                          \--valid--> Idempotent Receiver --new--> confirmed publish
 *                                                                                           \--seen--> duplicates
 * </pre>
 */
@Slf4j
@Configuration
@Profile("flow")
public class OrderFlowConfiguration {

  @Bean
  JdbcMetadataStore orderMemory(DataSource dataSource) {
    JdbcMetadataStore store = new JdbcMetadataStore(dataSource);
    store.setRegion("orders");
    return store;
  }

  @Bean
  JdbcMetadataStore fileMemory(DataSource dataSource) {
    JdbcMetadataStore store = new JdbcMetadataStore(dataSource);
    store.setRegion("files");
    return store;
  }

  @Bean
  FlowCounters flowCounters() {
    return new FlowCounters();
  }

  @Bean
  ConfirmedPublisher confirmedPublisher(RabbitTemplate template, OrderflowProperties properties) {
    return new ConfirmedPublisher(template, properties.confirmTimeoutMillis());
  }

  /**
   * Channel Adapter + Splitter: poll the folder (a file is fetched once, remembered in the
   * database), one message per line.
   */
  @Bean
  IntegrationFlow ingestFlow(
      SessionFactory<SftpClient.DirEntry> sftpSessions,
      ConcurrentMetadataStore fileMemory,
      OrderflowProperties properties) {
    return IntegrationFlow.from(
            Sftp.inboundAdapter(sftpSessions)
                .remoteDirectory(properties.sftp().remoteDirectory())
                .regexFilter(".*\\.csv")
                .filter(new SftpPersistentAcceptOnceFileListFilter(fileMemory, "sftp:"))
                .localDirectory(properties.inbox().toFile())
                .autoCreateLocalDirectory(true),
            spec ->
                spec.poller(
                    Pollers.fixedDelay(Duration.ofSeconds(properties.pollSeconds()))
                        .maxMessagesPerPoll(1)))
        .split(Files.splitter().markers())
        .<Object, Boolean>route(
            payload -> payload instanceof FileSplitter.FileMarker,
            mapping ->
                mapping
                    .subFlowMapping(true, branch -> branch.channel("fileMarkers"))
                    .subFlowMapping(false, branch -> branch.channel("lines")))
        .get();
  }

  /**
   * Message Filter (drop the header), Message Translator (line to order), Content-Based Router
   * (valid or not).
   */
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

  /**
   * Dead Letter Channel: the line and the reason go to a queue, confirmed, instead of being lost.
   */
  @Bean
  IntegrationFlow invalidFlow(ConfirmedPublisher publisher, FlowCounters counters) {
    return IntegrationFlow.from("invalid")
        .<Result<Order, Rejection>>handle(
            (result, headers) -> {
              Rejection rejection =
                  result.map(order -> (Rejection) null).recoverError(Function.identity());
              publisher.publishDeadLetter(
                  rejection.line(), rejection.reason(), String.valueOf(headers.get("file_name")));
              counters.deadLettered();
              log.info("DEAD LETTER: {} reason={}", rejection.line(), rejection.reason());
              return null;
            })
        .get();
  }

  /**
   * Idempotent Receiver, then a confirmed publish, then the memory. The id is remembered only AFTER
   * the broker has confirmed the order: a crash between the two publishes the order again on
   * restart (the order service absorbs it), where remembering first would lose it for good. The
   * check is a plain lookup, not {@code MetadataStoreSelector}'s put-then-pass.
   */
  @Bean
  IntegrationFlow validFlow(
      ConcurrentMetadataStore orderMemory, ConfirmedPublisher publisher, FlowCounters counters) {
    return IntegrationFlow.from("valid")
        .filter(
            Order.class,
            order -> orderMemory.get(order.orderId()) == null,
            spec -> spec.discardChannel("duplicates"))
        .<Order>handle(
            (order, headers) -> {
              publisher.publishOrder(order, String.valueOf(headers.get("file_name")));
              orderMemory.putIfAbsent(order.orderId(), "published");
              counters.published();
              return null;
            })
        .get();
  }

  @Bean
  IntegrationFlow duplicatesFlow(FlowCounters counters) {
    return IntegrationFlow.from("duplicates").handle(message -> counters.duplicate()).get();
  }

  /** The markers around a file: reset the counters at the start, report and clean up at the end. */
  @Bean
  IntegrationFlow markerFlow(FlowCounters counters) {
    return IntegrationFlow.from("fileMarkers")
        .<FileSplitter.FileMarker>handle(
            (marker, headers) -> {
              new FileSummary(counters).on(marker);
              return null;
            })
        .get();
  }

  private Order order(Result<Order, Rejection> result) {
    return result.map(Function.identity()).discardError().orElseThrow();
  }
}
