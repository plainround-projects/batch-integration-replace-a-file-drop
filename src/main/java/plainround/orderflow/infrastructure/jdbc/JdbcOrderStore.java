package plainround.orderflow.infrastructure.jdbc;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import plainround.orderflow.application.OrderStore;
import plainround.orderflow.domain.Order;

/**
 * The new path's table. The primary key is the idempotent consumer: a second delivery inserts
 * nothing.
 */
@RequiredArgsConstructor
public class JdbcOrderStore implements OrderStore {

  private final JdbcTemplate jdbc;

  @Override
  public boolean saveIfAbsent(Order order) {
    return jdbc.update(
            "INSERT INTO orders_events (order_id, customer, type, amount) VALUES (?, ?, ?, ?) "
                + "ON CONFLICT (order_id) DO NOTHING",
            order.orderId(),
            order.customer(),
            order.type().queue(),
            order.amount())
        == 1;
  }
}
