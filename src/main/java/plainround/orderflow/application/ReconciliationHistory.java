package plainround.orderflow.application;

import java.util.List;
import plainround.orderflow.domain.ReconciliationReport;

/** The port to the record of nightly comparisons. */
public interface ReconciliationHistory {

  /** Appends a report. */
  void record(ReconciliationReport report);

  /** The latest reports, newest first. */
  List<ReconciliationReport> latest(int count);
}
