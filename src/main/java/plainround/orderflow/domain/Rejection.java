package plainround.orderflow.domain;

/** A line that cannot become an order, with the reason: what the dead-letter channel carries. */
public record Rejection(String line, String reason) {}
