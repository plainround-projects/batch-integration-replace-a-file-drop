package plainround.orderflow.interfaces;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.sftp.session.SftpRemoteFileTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import plainround.orderflow.application.GeneratePartnerFile;
import plainround.orderflow.application.ProcessOrderMessage;
import plainround.orderflow.application.ReconcileOrders;
import plainround.orderflow.domain.RetirementPolicy;
import plainround.orderflow.infrastructure.OrderflowProperties;
import plainround.orderflow.infrastructure.jdbc.JdbcOrderStore;
import plainround.orderflow.infrastructure.jdbc.JdbcReconciliation;
import plainround.orderflow.infrastructure.jdbc.LegacyBatchLoader;
import plainround.orderflow.infrastructure.sftp.SftpPartnerDrop;

/** The composition root: the use cases get their adapters here and nowhere else. */
@Configuration
public class ApplicationWiring {

  @Bean
  ProcessOrderMessage processOrderMessage(JdbcTemplate jdbc) {
    return new ProcessOrderMessage(new JdbcOrderStore(jdbc));
  }

  @Bean
  ReconcileOrders reconcileOrders(JdbcTemplate jdbc, OrderflowProperties properties) {
    JdbcReconciliation reconciliation = new JdbcReconciliation(jdbc);
    return new ReconcileOrders(
        reconciliation, reconciliation, new RetirementPolicy(properties.cleanNightsToRetire()));
  }

  @Bean
  GeneratePartnerFile generatePartnerFile() {
    return new GeneratePartnerFile();
  }

  @Bean
  SftpPartnerDrop partnerDrop(SftpRemoteFileTemplate template, OrderflowProperties properties) {
    return new SftpPartnerDrop(template, properties);
  }

  @Bean
  LegacyBatchLoader legacyBatchLoader(
      SftpRemoteFileTemplate template,
      SftpPartnerDrop drop,
      JdbcTemplate jdbc,
      PlatformTransactionManager transactions,
      OrderflowProperties properties) {
    return new LegacyBatchLoader(
        template,
        drop,
        jdbc,
        new TransactionTemplate(transactions),
        properties.sftp().remoteDirectory());
  }
}
