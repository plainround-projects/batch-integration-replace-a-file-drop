package plainround.orderflow.infrastructure.amqp;

import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import plainround.orderflow.domain.Order;

/**
 * Publishes and waits for the broker's confirmation on a dedicated channel. A nack or a timeout is
 * an exception, so the flow knows the order did not arrive and the idempotent receiver can forget
 * it. Messages are persistent and the queues durable: a confirmed order survives a broker restart.
 */
@RequiredArgsConstructor
public class ConfirmedPublisher {

  private final RabbitTemplate template;
  private final long confirmTimeoutMillis;

  /**
   * Sends the order to the queue of its type; returns when the broker has taken responsibility for
   * it.
   */
  public void publishOrder(Order order, String partnerFile) {
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
        });
  }

  /** Sends a refused line to the dead-letter queue with the reason, confirmed the same way. */
  public void publishDeadLetter(String body, String reason, String partnerFile) {
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
        });
  }
}
