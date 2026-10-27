package plainround.orderflow.infrastructure.amqp;

import java.util.Map;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The broker's side of the design. Four durable queues: one per order type; {@code dead-letter} for
 * lines the flow refuses (published with their reason); {@code rejected} for orders the service
 * could not process after its retries (the two order queues dead-letter into it through the default
 * exchange).
 */
@Configuration
public class RabbitTopology {

  public static final String DEAD_LETTER = "dead-letter";
  public static final String REJECTED = "rejected";

  @Bean
  Declarables queues() {
    Map<String, Object> toRejected =
        Map.of("x-dead-letter-exchange", "", "x-dead-letter-routing-key", REJECTED);
    return new Declarables(
        order("retail", toRejected),
        order("wholesale", toRejected),
        QueueBuilder.durable(DEAD_LETTER).build(),
        QueueBuilder.durable(REJECTED).build());
  }

  private Queue order(String name, Map<String, Object> arguments) {
    return QueueBuilder.durable(name).withArguments(arguments).build();
  }

  @Bean
  MessageConverter messageConverter() {
    return new Jackson2JsonMessageConverter();
  }
}
