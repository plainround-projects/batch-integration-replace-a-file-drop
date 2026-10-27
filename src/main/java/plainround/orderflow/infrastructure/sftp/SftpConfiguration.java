package plainround.orderflow.infrastructure.sftp;

import org.apache.sshd.sftp.client.SftpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.file.remote.session.CachingSessionFactory;
import org.springframework.integration.file.remote.session.SessionFactory;
import org.springframework.integration.sftp.session.DefaultSftpSessionFactory;
import org.springframework.integration.sftp.session.SftpRemoteFileTemplate;
import plainround.orderflow.infrastructure.OrderflowProperties;

/** The SFTP client side: one cached session factory and a template over it. */
@Configuration
@EnableConfigurationProperties(OrderflowProperties.class)
public class SftpConfiguration {

  @Bean
  SessionFactory<SftpClient.DirEntry> sftpSessions(OrderflowProperties properties) {
    DefaultSftpSessionFactory factory = new DefaultSftpSessionFactory();
    factory.setHost(properties.sftp().host());
    factory.setPort(properties.sftp().port());
    factory.setUser(properties.sftp().user());
    factory.setPassword(properties.sftp().password());
    // The local container generates a new host key every time it starts; production pins the
    // partner's key.
    factory.setAllowUnknownKeys(true);
    return new CachingSessionFactory<>(factory, 4);
  }

  @Bean
  SftpRemoteFileTemplate sftpTemplate(SessionFactory<SftpClient.DirEntry> sftpSessions) {
    return new SftpRemoteFileTemplate(sftpSessions);
  }
}
