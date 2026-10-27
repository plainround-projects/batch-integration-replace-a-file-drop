package plainround.orderflow.application;

import java.util.List;
import plainround.orderflow.domain.ReconciliationReport;

/** The port to the comparison of the two paths. */
public interface ReconciliationSource {

  /** The four counts. */
  ReconciliationReport compare();

  /** Up to {@code limit} lines describing the orders behind the differences. */
  List<String> differences(int limit);
}
