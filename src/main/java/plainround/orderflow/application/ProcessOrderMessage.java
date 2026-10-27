package plainround.orderflow.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import plainround.orderflow.domain.Order;

/**
 * The order service's use case: apply one delivered order. The broker delivers at least once, so
 * the same order can arrive twice (a redelivery after a restart, a message republished by the
 * flow); the store's key makes the second delivery a no-op.
 */
@Slf4j
@RequiredArgsConstructor
public class ProcessOrderMessage {

  /** What a delivery did. */
  public enum Outcome {
    STORED,
    DUPLICATE
  }

  private final OrderStore store;

  /** Stores the order, or notes that it was already there. */
  public Outcome handle(Order order) {
    boolean fresh = store.saveIfAbsent(order);
    log.debug("order {} {}", order.orderId(), fresh ? "stored" : "already stored");
    return fresh ? Outcome.STORED : Outcome.DUPLICATE;
  }
}
