package plainround.orderflow.domain;

import java.util.List;

/**
 * The condition for retiring the legacy file path, as a number: this many nights in a row with a
 * clean reconciliation. One clean night proves little; a week of files can still hide the one
 * partner behavior that happens on the first of the month.
 */
public record RetirementPolicy(int cleanNightsRequired) {

  public RetirementPolicy {
    if (cleanNightsRequired < 1) {
      throw new IllegalArgumentException("cleanNightsRequired must be at least 1");
    }
  }

  /** True when the latest {@code cleanNightsRequired} reports (newest first) are all clean. */
  public boolean canRetire(List<ReconciliationReport> newestFirst) {
    return newestFirst.size() >= cleanNightsRequired
        && newestFirst.stream().limit(cleanNightsRequired).allMatch(ReconciliationReport::isClean);
  }
}
