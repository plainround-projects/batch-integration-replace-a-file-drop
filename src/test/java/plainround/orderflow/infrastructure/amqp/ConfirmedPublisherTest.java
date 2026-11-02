package plainround.orderflow.infrastructure.amqp;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.instancio.Instancio;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import plainround.orderflow.domain.Order;

class ConfirmedPublisherTest {

  private final RabbitTemplate template = mock(RabbitTemplate.class);
  private final ConfirmedPublisher publisher =
      new ConfirmedPublisher(
          template, 5000, ConfirmedPublisher.whileBrokerAway(Duration.ofMillis(1)));
  private final Order order = Instancio.create(Order.class);

  @Test
  void a_publish_is_tried_again_until_the_broker_is_back() {
    when(template.invoke(any()))
        .thenThrow(new AmqpConnectException(new java.net.ConnectException("refused")))
        .thenThrow(new AmqpConnectException(new java.net.ConnectException("refused")))
        .thenReturn(null);

    publisher.publishOrder(order, "orders-1.csv");

    verify(template, times(3)).invoke(any());
  }

  @Test
  void an_error_that_is_not_about_the_broker_is_not_retried() {
    when(template.invoke(any())).thenThrow(new IllegalStateException("bug"));

    assertThatThrownBy(() -> publisher.publishDeadLetter("x", "why", "orders-1.csv"))
        .isInstanceOf(IllegalStateException.class);

    verify(template, times(1)).invoke(any());
  }

  @Test
  void a_nack_or_timeout_that_the_broker_reports_as_an_amqp_error_is_retried() {
    when(template.invoke(any())).thenThrow(new AmqpException("nack")).thenReturn(null);

    publisher.publishOrder(order, "orders-1.csv");

    verify(template, times(2)).invoke(any());
  }
}
