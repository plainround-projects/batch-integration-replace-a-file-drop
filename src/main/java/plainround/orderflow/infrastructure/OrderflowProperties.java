package plainround.orderflow.infrastructure;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The settings of the flow and the tools, validated at startup.
 *
 * @param sftp where the partner drops files
 * @param inbox where the SFTP adapter keeps a local copy while a file is processed
 * @param pollSeconds seconds between looks at the SFTP folder
 * @param confirmTimeoutMillis how long a publish waits for the broker's confirmation before it
 *     counts as failed
 * @param cleanNightsToRetire nights in a row without a difference before the legacy path may be
 *     retired
 */
@Validated
@ConfigurationProperties("orderflow")
public record OrderflowProperties(
    @Valid @NotNull Sftp sftp,
    @DefaultValue("data/inbox") @NotNull Path inbox,
    @DefaultValue("5") @Min(1) int pollSeconds,
    @DefaultValue("5000") @Min(100) long confirmTimeoutMillis,
    @DefaultValue("14") @Min(1) int cleanNightsToRetire) {

  /** The partner's SFTP folder. */
  public record Sftp(
      @DefaultValue("localhost") @NotBlank String host,
      @DefaultValue("2222") @Min(1) int port,
      @DefaultValue("partner") @NotBlank String user,
      @DefaultValue("") String password,
      @DefaultValue("upload") @NotBlank String remoteDirectory) {}
}
