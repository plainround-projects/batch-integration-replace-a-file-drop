package plainround.orderflow.domain;

import com.github.sviperll.result4j.Result;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The Message Translator and the Message Filter of the flow, as one pure function: a line of the
 * partner's file becomes an {@link Order}, or a {@link Rejection} saying why not. No framework, so
 * every rule is testable on its own.
 */
public final class OrderParser {

  private static final int FIELDS = 4;

  private OrderParser() {}

  /** Parses {@code order_id,customer,type,amount}. The first rule that fails names the reason. */
  public static Result<Order, Rejection> parse(String line) {
    List<String> fields = Arrays.stream(line.split(",", -1)).map(String::strip).toList();
    return fields.size() != FIELDS
        ? reject(line, "malformed line: expected 4 fields")
        : build(line, fields);
  }

  private static Result<Order, Rejection> build(String line, List<String> fields) {
    Optional<OrderType> type = OrderType.parse(fields.get(2));
    return problem(fields, type)
        .<Result<Order, Rejection>>map(reason -> reject(line, reason))
        .orElseGet(
            () ->
                Result.success(
                    new Order(
                        fields.get(0),
                        fields.get(1),
                        type.orElseThrow(),
                        new BigDecimal(fields.get(3)))));
  }

  private static Optional<String> problem(List<String> fields, Optional<OrderType> type) {
    return Stream.of(
            Optional.of("order id missing").filter(reason -> fields.get(0).isEmpty()),
            Optional.of("customer missing").filter(reason -> fields.get(1).isEmpty()),
            Optional.of("unknown order type " + fields.get(2)).filter(reason -> type.isEmpty()),
            amountProblem(fields.get(3)))
        .flatMap(Optional::stream)
        .findFirst();
  }

  private static Optional<String> amountProblem(String amount) {
    if (amount.isEmpty()) {
      return Optional.of("amount missing");
    }
    return Optional.of(amount)
        .filter(text -> !text.matches("-?\\d+(\\.\\d{1,2})?"))
        .map(text -> "amount is not a number with at most two decimals")
        .or(
            () ->
                Optional.of(amount)
                    .filter(text -> new BigDecimal(text).signum() <= 0)
                    .map(text -> "amount must be above zero"));
  }

  private static Result<Order, Rejection> reject(String line, String reason) {
    return Result.error(new Rejection(line, reason));
  }
}
