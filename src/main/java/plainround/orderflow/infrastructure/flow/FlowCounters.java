package plainround.orderflow.infrastructure.flow;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * What the flow did with the file in flight. The flow is single-threaded per file, so plain
 * counters are enough.
 */
public class FlowCounters {

  private final AtomicInteger valid = new AtomicInteger();
  private final AtomicInteger deadLettered = new AtomicInteger();
  private final AtomicInteger duplicates = new AtomicInteger();
  private final AtomicInteger published = new AtomicInteger();

  /** Starts counting a new file. */
  public void reset() {
    valid.set(0);
    deadLettered.set(0);
    duplicates.set(0);
    published.set(0);
  }

  public void valid() {
    valid.incrementAndGet();
  }

  public void deadLettered() {
    deadLettered.incrementAndGet();
  }

  public void duplicate() {
    duplicates.incrementAndGet();
  }

  public void published() {
    published.incrementAndGet();
  }

  public int validCount() {
    return valid.get();
  }

  public int deadLetteredCount() {
    return deadLettered.get();
  }

  public int duplicateCount() {
    return duplicates.get();
  }

  public int publishedCount() {
    return published.get();
  }
}
