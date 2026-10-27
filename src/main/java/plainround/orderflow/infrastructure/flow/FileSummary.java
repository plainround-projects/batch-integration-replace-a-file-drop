package plainround.orderflow.infrastructure.flow;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.integration.file.splitter.FileSplitter;

/**
 * Reports what the flow did with a file when its end marker arrives, then removes the local copy.
 */
@Slf4j
@RequiredArgsConstructor
class FileSummary {

  private final FlowCounters counters;

  void on(FileSplitter.FileMarker marker) {
    if (marker.getMark() == FileSplitter.FileMarker.Mark.START) {
      counters.reset();
    } else {
      report(marker);
    }
  }

  private void report(FileSplitter.FileMarker marker) {
    log.info(
        "flow: split {} lines -> {} orders (header dropped)",
        marker.getLineCount(),
        marker.getLineCount() - 1);
    log.info("flow: {} to dead letter", counters.deadLetteredCount());
    log.info("flow: {} duplicate(s) dropped", counters.duplicateCount());
    log.info("flow: {} published", counters.publishedCount());
    delete(marker.getFilePath());
  }

  private void delete(String path) {
    try {
      Files.deleteIfExists(java.nio.file.Path.of(path));
    } catch (IOException problem) {
      throw new UncheckedIOException(problem);
    }
  }
}
