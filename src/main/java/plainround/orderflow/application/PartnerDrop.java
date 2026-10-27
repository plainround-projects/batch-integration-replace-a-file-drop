package plainround.orderflow.application;

import java.nio.file.Path;

/** The port to the partner's SFTP folder. */
public interface PartnerDrop {

  /** Uploads the file into the folder the flow and the legacy loader both read. */
  void upload(Path file);
}
