package plainround.orderflow.infrastructure.amqp;

import java.time.Duration;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.retry.support.RetryTemplate;
import plainround.orderflow.domain.Order;

/**
 * Publishes and waits for the broker's confirmation on a dedicated channel. A nack or a timeout is
 * an exception, so the flow knows the order did not arrive and the idempotent receiver can forget
 * it. Messages are persistent and the queues durable: a confirmed order survives a broker restart.
 *
 * <p>While the broker is away (a restart takes seconds, sometimes a minute) a publish is tried
 * again every two seconds for up to five minutes instead of failing, because a failure in the
 * middle of a file would leave the rest of that file unpublished. A retry can publish an order
 * twice (the confirm was lost, not the order); the order service stores it once.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class ConfirmedPublisher {

  private final RabbitTemplate template;
  private final long confirmTimeoutMillis;

  private final RetryTemplate brokerAway;

  /** Tries again every two seconds for up to five minutes while the broker is away. */
  public ConfirmedPublisher(RabbitTemplate template, long confirmTimeoutMillis) {
    this(template, confirmTimeoutMillis, whileBrokerAway(Duration.ofSeconds(2)));
  }

  static RetryTemplate whileBrokerAway(Duration pause) {
    return RetryTemplate.builder()
        .retryOn(AmqpException.class)
        .traversingCauses()
        .fixedBackoff(pause)
        .withTimeout(Duration.ofMinutes(5))
        .build();
  }

  /**
   * Sends the order to the queue of its type; returns when the broker has taken responsibility for
   * it.
   */
  public void publishOrder(Order order, String partnerFile) {
    brokerAway.execute(
        attempt ->
            template.invoke(
                operations -> {
                  operations.convertAndSend(
                      "",
                      order.type().queue(),
                      order,
                      message -> {
                        message.getMessageProperties().setHeader("partnerFile", partnerFile);
                        return message;
                      });
                  operations.waitForConfirmsOrDie(confirmTimeoutMillis);
                  return null;
                }));
  }

  /** Sends a refused line to the dead-letter queue with the reason, confirmed the same way. */
  public void publishDeadLetter(String body, String reason, String partnerFile) {
    brokerAway.execute(
        attempt ->
            template.invoke(
                operations -> {
                  operations.convertAndSend(
                      "",
                      RabbitTopology.DEAD_LETTER,
                      body,
                      message -> {
                        message.getMessageProperties().setHeader("reason", reason);
                        message.getMessageProperties().setHeader("partnerFile", partnerFile);
                        return message;
                      });
                  operations.waitForConfirmsOrDie(confirmTimeoutMillis);
                  return null;
                }));
  }
}
