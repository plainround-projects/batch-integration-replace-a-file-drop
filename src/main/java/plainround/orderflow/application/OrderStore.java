package plainround.orderflow.application;

import plainround.orderflow.domain.Order;

/** The port to the new path's table. */
public interface OrderStore {

  /**
   * Stores the order unless its id exists; true when it was new. Repeating a delivery changes
   * nothing.
   */
  boolean saveIfAbsent(Order order);
}
