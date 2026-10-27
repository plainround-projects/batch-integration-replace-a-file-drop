package plainround.orderflow.interfaces;

import java.util.Arrays;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Entry point for all the programs of the project. Which one runs is decided by the profile ({@code
 * flow}, {@code order-service}) for the two long-running services, and by {@code --command=} for
 * the tools.
 */
@SpringBootApplication(scanBasePackages = "plainround.orderflow")
public class OrderFlowApplication {

  public static void main(String[] args) throws InterruptedException {
    ConfigurableApplicationContext context =
        SpringApplication.run(OrderFlowApplication.class, args);
    if (serving(args)) {
      // The long-running services stay up until a signal arrives; the shutdown hook then closes the
      // context, which stops the pollers and the listeners after the work in hand.
      Thread.currentThread().join();
    }
    System.exit(SpringApplication.exit(context));
  }

  private static boolean serving(String[] args) {
    List<String> commands =
        Arrays.stream(args).filter(argument -> argument.startsWith("--command=")).toList();
    return commands.isEmpty() || commands.contains("--command=serve");
  }
}
