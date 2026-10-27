package plainround.orderflow.infrastructure.amqp;

import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import plainround.orderflow.application.ProcessOrderMessage;
import plainround.orderflow.domain.Order;

/**
 * The order service's consumer. A message that cannot be converted is rejected without requeue at
 * once; one that fails while being processed is retried three times (see {@code application.yml})
 * and then rejected, and the queue's dead-letter setting moves it to {@code rejected} instead of
 * looping for ever.
 */
@Component
@Profile("order-service")
@RequiredArgsConstructor
public class OrderListener {

  private final ProcessOrderMessage process;

  @RabbitListener(queues = {"retail", "wholesale"})
  public void onOrder(Order order) {
    process.handle(order);
  }
}
