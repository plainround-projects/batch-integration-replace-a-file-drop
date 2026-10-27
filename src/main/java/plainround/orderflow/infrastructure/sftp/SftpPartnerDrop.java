package plainround.orderflow.infrastructure.sftp;

import java.io.File;
import java.nio.file.Path;
import org.apache.sshd.sftp.client.SftpClient;
import org.springframework.expression.common.LiteralExpression;
import org.springframework.integration.sftp.session.SftpRemoteFileTemplate;
import org.springframework.integration.support.MessageBuilder;
import plainround.orderflow.application.PartnerDrop;
import plainround.orderflow.infrastructure.OrderflowProperties;

/** Uploads a file into the partner's folder, as the partner's own system would. */
public class SftpPartnerDrop implements PartnerDrop {

  private final SftpRemoteFileTemplate template;
  private final String directory;

  public SftpPartnerDrop(SftpRemoteFileTemplate template, OrderflowProperties properties) {
    this.template = template;
    this.directory = properties.sftp().remoteDirectory();
    this.template.setRemoteDirectoryExpression(
        new LiteralExpression(properties.sftp().remoteDirectory()));
    this.template.setAutoCreateDirectory(false);
  }

  @Override
  public void upload(Path file) {
    template.send(MessageBuilder.<File>withPayload(file.toFile()).build(), "");
  }

  /** Names of the files in the partner's folder, for the status command. */
  public java.util.List<String> list() {
    return java.util.Arrays.stream(template.list(directory))
        .map(SftpClient.DirEntry::getFilename)
        .filter(name -> name.endsWith(".csv"))
        .sorted()
        .toList();
  }

  /** Removes every CSV from the folder (the tests start from an empty one). */
  public void clear() {
    list().forEach(name -> template.remove(directory + "/" + name));
  }
}
