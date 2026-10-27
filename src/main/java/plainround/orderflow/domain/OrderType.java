package plainround.orderflow.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * The two kinds of order the partner sends; the routing key and the queue are the lower-case name.
 */
public enum OrderType {
  RETAIL,
  WHOLESALE;

  /** The queue (and routing key) for this type. */
  public String queue() {
    return name().toLowerCase();
  }

  /** The type named by a file field, ignoring case; empty for anything else. */
  public static Optional<OrderType> parse(String field) {
    return Arrays.stream(values())
        .filter(type -> type.queue().equalsIgnoreCase(field == null ? "" : field.strip()))
        .findFirst();
  }
}
