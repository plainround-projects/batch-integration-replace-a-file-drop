package plainround.orderflow.domain;

/** The four counts of the morning comparison of the two paths. */
public record ReconciliationReport(long onlyInBatch, long onlyInEvents, long different, long same) {

  /** True when the paths agree completely: nothing on one side only, nothing different. */
  public boolean isClean() {
    return onlyInBatch == 0 && onlyInEvents == 0 && different == 0;
  }
}
