package plainround.orderflow.application;

import java.util.List;
import lombok.RequiredArgsConstructor;
import plainround.orderflow.domain.ReconciliationReport;
import plainround.orderflow.domain.RetirementPolicy;

/**
 * The morning job: compare the paths, remember the result, say whether the legacy path may be
 * retired.
 */
@RequiredArgsConstructor
public class ReconcileOrders {

  /** The night's counts, the orders behind any difference, and the cutover verdict. */
  public record Outcome(
      ReconciliationReport report, List<String> differences, boolean mayRetireLegacy) {

    /** 0 for a clean night, 1 for any difference. */
    public int exitCode() {
      return report.isClean() ? 0 : 1;
    }
  }

  private static final int LISTED_DIFFERENCES = 20;

  private final ReconciliationSource source;
  private final ReconciliationHistory history;
  private final RetirementPolicy policy;

  /** Runs the comparison and records it. */
  public Outcome run() {
    ReconciliationReport report = source.compare();
    history.record(report);
    List<String> differences =
        report.isClean() ? List.of() : source.differences(LISTED_DIFFERENCES);
    return new Outcome(
        report, differences, policy.canRetire(history.latest(policy.cleanNightsRequired())));
  }
}
