package plainround.orderflow.interfaces;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for all the programs of the project. Which one runs is decided by the profile ({@code
 * flow}, {@code order-service}) for the two long-running services, and by {@code --command=} for
 * the tools.
 */
@SpringBootApplication(scanBasePackages = "plainround.orderflow")
public class OrderFlowApplication {

  public static void main(String[] args) {
    System.exit(SpringApplication.exit(SpringApplication.run(OrderFlowApplication.class, args)));
  }
}
