package plainround.orderflow.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.sviperll.result4j.Result;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

class DomainTest {

  private String reason(String line) {
    return OrderParser.parse(line).map(order -> "valid").recoverError(Rejection::reason);
  }

  @Test
  void a_good_line_becomes_an_order() {
    Result<Order, Rejection> parsed = OrderParser.parse("O000010,C0042,retail,123.45");
    assertThat(parsed.discardError())
        .contains(new Order("O000010", "C0042", OrderType.RETAIL, new BigDecimal("123.45")));
  }

  @Test
  void the_type_is_case_insensitive_and_whitespace_is_ignored() {
    assertThat(OrderParser.parse(" O1 , C1 , Wholesale , 10 ").discardError())
        .get()
        .extracting(Order::type)
        .isEqualTo(OrderType.WHOLESALE);
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "O999001,C0001,retail,|amount missing",
        "O999002,C0002,retail,-4|amount must be above zero",
        "O999004,C0002,retail,0|amount must be above zero",
        "O999003,C0003,barter,10|unknown order type barter",
        ",C1,retail,10|order id missing",
        "O1,,retail,10|customer missing",
        "O1,C1,retail,abc|amount is not a number with at most two decimals",
        "O1,C1,retail,10.123|amount is not a number with at most two decimals",
        "O1,C1,retail|malformed line: expected 4 fields",
        "O1,C1,retail,10,extra|malformed line: expected 4 fields"
      })
  void a_bad_line_is_refused_with_the_reason_the_dead_letter_queue_will_carry(
      String line, String expected) {
    assertThat(reason(line)).isEqualTo(expected);
  }

  @Test
  void the_rejection_keeps_the_original_line() {
    Rejection rejection =
        OrderParser.parse("O1,C1,barter,10").map(order -> (Rejection) null).recoverError(r -> r);
    assertThat(rejection.line()).isEqualTo("O1,C1,barter,10");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @CsvSource({"barter", "RETAILS"})
  void an_unknown_type_is_empty(String field) {
    assertThat(OrderType.parse(field)).isEmpty();
  }

  @Test
  void the_queue_of_a_type_is_its_lower_case_name() {
    assertThat(OrderType.RETAIL.queue()).isEqualTo("retail");
    assertThat(OrderType.WHOLESALE.queue()).isEqualTo("wholesale");
  }

  @Test
  void a_report_is_clean_only_when_nothing_differs_on_either_side() {
    assertThat(new ReconciliationReport(0, 0, 0, 9996).isClean()).isTrue();
    assertThat(new ReconciliationReport(1, 0, 0, 9996).isClean()).isFalse();
    assertThat(new ReconciliationReport(0, 1, 0, 9996).isClean()).isFalse();
    assertThat(new ReconciliationReport(0, 0, 1, 9996).isClean()).isFalse();
  }

  private ReconciliationReport clean() {
    return new ReconciliationReport(0, 0, 0, 100);
  }

  @Test
  void the_legacy_path_may_retire_only_after_enough_clean_nights_in_a_row() {
    RetirementPolicy policy = new RetirementPolicy(3);
    assertThat(policy.canRetire(List.of(clean(), clean()))).isFalse();
    assertThat(policy.canRetire(List.of(clean(), clean(), clean()))).isTrue();
    assertThat(
            policy.canRetire(
                List.of(clean(), new ReconciliationReport(0, 0, 1, 99), clean(), clean())))
        .isFalse();
    assertThat(
            policy.canRetire(
                List.of(clean(), clean(), clean(), new ReconciliationReport(5, 0, 0, 1))))
        .isTrue();
  }

  @Test
  void a_policy_needs_at_least_one_night() {
    assertThatThrownBy(() -> new RetirementPolicy(0)).hasMessageContaining("at least 1");
  }
}
