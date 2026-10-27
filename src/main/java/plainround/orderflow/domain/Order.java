package plainround.orderflow.domain;

import java.math.BigDecimal;

/** One valid order from the partner. The partner's order id is the business key at every hop. */
public record Order(String orderId, String customer, OrderType type, BigDecimal amount) {}
