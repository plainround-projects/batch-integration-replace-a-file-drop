# Spring Boot 3.5

## What it is

Spring is a very widely used Java toolkit for building applications out of small parts that are given to each other, instead of each part building the others itself. **Spring Boot** is the layer on top that removes the setup work: it looks at which libraries are on the classpath (the list of code the program can use), creates sensible default configuration for them, and starts the program. You add a library, and Boot sets it up (this is called **auto-configuration**).

The central idea is **dependency injection**. Imagine a class `ProcessOrderMessage` that needs an `OrderStore`. Without Spring it would write `new JdbcOrderStore(...)` itself, which ties it to one implementation and makes testing hard. With Spring, the class only declares what it needs (a constructor parameter), and Spring creates the objects and passes them in. The objects Spring creates and manages are called **beans**; the box that holds them is the **application context**.

You tell Spring about beans in two ways. A class annotated `@Component` (or `@Service`, `@Configuration`) is found by **component scanning** and one instance is created. A method annotated `@Bean` inside a `@Configuration` class is a recipe: Spring calls the method and keeps the returned object as a bean, giving the method its parameters from other beans.

## Why this repository uses it

Spring Integration, the RabbitMQ client and the JDBC support all run inside a Spring application, and Boot is the simplest way to start one. Boot also reads the settings (database, broker and SFTP addresses) from the environment, builds the connection pool and the RabbitMQ connection, and lets one program play several roles: **profiles** decide whether this process is the file flow, the order service or a command-line tool.

## Where it appears

| Path | What it does with Spring Boot |
| --- | --- |
| `${I}/OrderFlowApplication.java` | The `main` method: starts Boot, stays alive for the services, and turns the result into an exit code. |
| `${I}/ApplicationWiring.java` | The composition root: the one place where the use cases are given their adapters. |
| `${I}/OrderflowRunner.java` | An `ApplicationRunner`: reads the command line and runs `partner`, `legacy-load`, `reconcile`, `status`, `health` or `serve`. |
| `src/main/resources/application.yml` | Default settings and the environment variables that override them. |
| `pom.xml` | Pulls in Spring Boot and its starters. |
| `docker-compose.yml` | Sets `SPRING_PROFILES_ACTIVE` for the two service containers. |

## How it works underneath

`SpringApplication.run(MainClass.class, args)` does the following. It builds an **environment**: a collection of settings gathered from several places, in this order of priority (the first that has a value wins): command-line arguments such as `--orderflow.poll-seconds=1`, operating-system **environment variables**, the file `application.yml` on the classpath, and defaults written in code. An environment variable is a named text value that the shell passes to a program (for example `ORDERFLOW_POLL_SECONDS=1 java -jar app.jar`). Boot's **relaxed binding** lets the same setting be written `orderflow.poll-seconds` in a file, `ORDERFLOW_POLL_SECONDS` as an environment variable and `--orderflow.poll-seconds=1` on the command line.

Next Boot scans the packages named by `@SpringBootApplication` for annotated classes, applies the auto-configurations for the libraries it finds (a database connection pool, the RabbitMQ connection, the logging system), creates every bean in dependency order, and calls any `ApplicationRunner` beans. When `run` returns, the program normally ends unless something keeps a non-daemon thread alive. A **daemon thread** is a background thread that does not keep the program alive; a normal (non-daemon) thread does.

`SpringApplication.exit(context)` closes the application context in an orderly way (stopping beans, closing the database pool) and returns the **exit code** collected from beans that implement `ExitCodeGenerator`. `System.exit(code)` then ends the process with that code. The exit code is how a program tells the shell whether it succeeded: 0 means success, anything else means failure.

## Walkthrough of this repository's use

### `OrderFlowApplication.java`: the entry point

```java
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
```

`@SpringBootApplication(scanBasePackages = "plainround.orderflow")` is three annotations in one: it marks a configuration class, switches on auto-configuration, and scans the named package and everything below it for `@Component` and `@Configuration` classes. `main` is where the operating system enters the program. `SpringApplication.run(OrderFlowApplication.class, args)` builds the application context and runs every `ApplicationRunner`. Then `serving(args)` decides what kind of process this is: it looks at the arguments for `--command=`; with none, or with `--command=serve`, this is a long-running service, so `Thread.currentThread().join()` makes the main thread wait for ever (a thread waiting to join itself never returns). The poller threads of the flow and the listener threads of the order service keep working in the background; when the operating system sends a termination signal, Boot's shutdown hook closes the context, which stops them after the work in hand. For a tool command (`partner`, `reconcile`, ...) the program does not wait: `System.exit(SpringApplication.exit(context))` closes the context and exits with the code the runner computed. `throws InterruptedException` is Java's way of saying `join()` can be interrupted.

The comment on the class names the two ways to choose a role: the profile (`flow`, `order-service`) for the services, and `--command=` for the tools.

### `ApplicationWiring.java`: the composition root

A **composition root** is the single place where the parts of a program are plugged together. The use cases in `application` know only interfaces; this class chooses the real implementations.

```java
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

```

The imports show every layer. `@Configuration` (line 20) says this class defines beans.

```java
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
```

Each method annotated `@Bean` creates one object that Spring keeps and hands to whoever needs it. `processOrderMessage` builds the order service's use case from the JDBC store. `reconcileOrders` builds the morning job: `JdbcReconciliation` implements both the `ReconciliationSource` and the `ReconciliationHistory` port, so the same object is passed twice, and `new RetirementPolicy(properties.cleanNightsToRetire())` turns the setting into a rule (14 by default). `generatePartnerFile` has no dependencies. `partnerDrop` builds the SFTP adapter. `legacyBatchLoader` receives a `PlatformTransactionManager` and wraps it in a `TransactionTemplate`, so the loader can run one database transaction per file. Spring finds the arguments of each method by type (`JdbcTemplate`, `OrderflowProperties`, `SftpRemoteFileTemplate`) among its own beans.

### `OrderflowRunner.java`: the command line

`ApplicationRunner` is a Boot interface: every bean that implements it has its `run` method called after startup. `ExitCodeGenerator` lets the bean supply the exit code.

```java
package plainround.orderflow.interfaces;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import plainround.orderflow.application.GeneratePartnerFile;
import plainround.orderflow.application.ReconcileOrders;
import plainround.orderflow.infrastructure.jdbc.LegacyBatchLoader;
import plainround.orderflow.infrastructure.sftp.SftpPartnerDrop;

/**
 * The tools: {@code --command=partner|legacy-load|reconcile|status|health|serve}. The long-running
 * services ({@code flow}, {@code order-service} profiles) use {@code serve}, which does nothing and
 * lets their threads keep the process alive.
 */
@Component
@RequiredArgsConstructor
public class OrderflowRunner implements ApplicationRunner, ExitCodeGenerator {

  private final GeneratePartnerFile generator;
  private final SftpPartnerDrop drop;
  private final LegacyBatchLoader legacy;
  private final ReconcileOrders reconcile;
  private final JdbcTemplate jdbc;
  private final AmqpAdmin admin;
  private volatile int exitCode;

```

`@Component` makes the class a bean found by scanning; `@RequiredArgsConstructor` (Lombok) writes the constructor for the six `final` fields. `AmqpAdmin` is Spring's administration interface to the broker, used here to ask for queue depths. `volatile int exitCode` is a number that every thread sees up to date.

```java
  @Override
  public void run(ApplicationArguments args) {
    Map<String, Function<ApplicationArguments, Integer>> commands =
        Map.of(
            "partner",
            this::partner,
            "legacy-load",
            this::legacyLoad,
            "reconcile",
            this::reconcile,
            "status",
            this::status,
            "health",
            this::health,
            "serve",
            arguments -> 0);
    exitCode = commands.getOrDefault(option(args, "command", "serve"), unknown()).apply(args);
  }

  @Override
  public int getExitCode() {
    return exitCode;
  }
```

`run` builds a map from command name to the method that handles it (`this::partner` is a method reference), reads the command from `--command=` (default `serve`), looks it up with `getOrDefault` (falling back to the unknown-command handler) and calls it; the integer it returns becomes the exit code. `getExitCode` is the `ExitCodeGenerator` method that `SpringApplication.exit` reads.

```java
  private int partner(ApplicationArguments args) {
    int night = Integer.parseInt(option(args, "night", "1"));
    Path file =
        Path.of(option(args, "out", "data"))
            .resolve(option(args, "name", "orders-" + night + ".csv"));
    file.getParent().toFile().mkdirs();
    int valid =
        generator.write(file, night, Long.parseLong(option(args, "seed", String.valueOf(night))));
    drop.upload(file);
    System.out.println(
        "uploaded " + file.getFileName() + " with " + valid + " valid distinct orders");
    return 0;
  }

  private int legacyLoad(ApplicationArguments args) {
    LegacyBatchLoader.Result result = legacy.loadNew();
    System.out.println(
        "legacy batch loaded " + result.files() + " file(s), " + result.orders() + " orders");
    return 0;
  }

  private int reconcile(ApplicationArguments args) {
    ReconcileOrders.Outcome outcome = reconcile.run();
    System.out.println(" only_in_batch | only_in_events | different | same");
    System.out.printf(
        "%14d | %14d | %9d | %5d%n",
        outcome.report().onlyInBatch(),
        outcome.report().onlyInEvents(),
        outcome.report().different(),
        outcome.report().same());
    outcome.differences().forEach(line -> System.out.println("  " + line));
    System.out.println(
        outcome.mayRetireLegacy()
            ? "legacy path may be retired"
            : "legacy path stays: not enough clean nights yet");
    return outcome.exitCode();
  }
```

`partner` reads `--night` (default 1), builds the file path from `--out` (default `data`) and `--name` (default `orders-<night>.csv`), creates the folder, generates the file with the night as default seed, uploads it to the SFTP server and prints `uploaded <file> with <n> valid distinct orders`. `legacyLoad` runs the stand-in for the old batch job and prints how many files and orders it loaded. `reconcile` runs the comparison and prints the four counts in a table (`%14d` pads a number to 14 characters), then up to 20 lines describing the orders behind any difference, then the verdict on retiring the legacy path; its return value is the outcome's exit code, 0 or 1.

```java
  private int status(ApplicationArguments args) {
    System.out.println("sftp files: " + drop.list());
    List.of("retail", "wholesale", "dead-letter", "rejected").forEach(this::queue);
    System.out.println(
        "orders_batch="
            + count("orders_batch")
            + " orders_events="
            + count("orders_events")
            + " seen_orders="
            + jdbc.queryForObject(
                "SELECT count(*) FROM int_metadata_store WHERE region = 'orders'", Long.class));
    return 0;
  }

  private void queue(String name) {
    QueueInformation info = admin.getQueueInfo(name);
    System.out.printf(
        "queue %-12s %d messages%n", name, info == null ? -1 : info.getMessageCount());
  }

  private Long count(String table) {
    return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
  }

  private int health(ApplicationArguments args) {
    jdbc.queryForObject("SELECT 1", Integer.class);
    admin.getQueueInfo("retail");
    System.out.println(
        "ok database, broker and sftp folder reachable (" + drop.list().size() + " file(s))");
    return 0;
  }

  private Function<ApplicationArguments, Integer> unknown() {
    return args -> {
      System.err.println("unknown command");
      return 2;
    };
  }

  private static String option(ApplicationArguments args, String name, String fallback) {
    return args.containsOption(name) ? args.getOptionValues(name).getFirst() : fallback;
  }
}
```

`status` prints the files in the partner's folder, the depth of each of the four queues (`admin.getQueueInfo(name)` returns `null` for a queue that does not exist, shown as `-1`), and the row counts of both tables plus the number of order ids the idempotent receiver remembers (`SELECT count(*) FROM int_metadata_store WHERE region = 'orders'`). `health` runs `SELECT 1`, asks the broker about the queue `retail`, lists the SFTP folder, and prints one line; any failure throws and the exit code is non-zero. `unknown` prints `unknown command` on standard error and returns 2. `option` reads `--name=value` arguments with a default. `System.out` is **standard output** (results); the JSON log goes to **standard error** (the Logback note).

### `application.yml`: defaults and the environment

YAML is a settings format (the YAML note).

```yaml
# Configuration from the environment (twelve-factor); the values below are defaults for the compose stack.
spring:
  application:
    name: order-flow
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:55561/orderflow}
    username: ${DB_USER:orderflow}
    password: ${DB_PASSWORD:}
    hikari:
      maximum-pool-size: 5
      connection-timeout: 20000
```

`${DB_URL:jdbc:postgresql://localhost:55561/orderflow}` means "the environment variable `DB_URL`, or this default": the address of the container started by `make up`. `hikari` is the connection pool (the JDBC note); `maximum-pool-size: 5` allows five connections, `connection-timeout: 20000` waits up to 20 seconds for one.

```yaml
  rabbitmq:
    host: ${RABBIT_HOST:localhost}
    port: ${RABBIT_PORT:56721}
    username: ${RABBIT_USER:orderflow}
    password: ${RABBIT_PASSWORD:}
    publisher-confirm-type: simple  # confirmSelect() on every channel, so the publisher can wait for the broker's confirm
    publisher-returns: true
    template:
      mandatory: true            # an unroutable message is returned, not silently dropped
    listener:
      simple:
        default-requeue-rejected: false # a rejected message goes to the dead-letter queue, not back to the head
        prefetch: 50
        retry:
          enabled: true          # three attempts, then rejected without requeue
          max-attempts: 3
          initial-interval: 500ms
          multiplier: 2.0
```

The RabbitMQ settings are in the RabbitMQ note; the three notable lines here: `publisher-confirm-type: simple` makes the client ask the broker to confirm each published message, `publisher-returns: true` and `mandatory: true` make an unroutable message come back instead of vanishing, and `listener.simple.retry` gives the consumer three attempts with waits of 0.5 s and then 1 s before the message is rejected.

```yaml
  main:
    banner-mode: off
    web-application-type: none
logging:
  level:
    root: INFO
    org.springframework.amqp: WARN
    org.apache.sshd: WARN

orderflow:
  sftp:
    host: ${SFTP_HOST:localhost}
    port: ${SFTP_PORT:56222}
    user: ${SFTP_USER:partner}
    password: ${SFTP_PASSWORD:}
    remote-directory: upload
  inbox: ${ORDERFLOW_INBOX:data/inbox}
  poll-seconds: ${ORDERFLOW_POLL_SECONDS:5}
  confirm-timeout-millis: ${ORDERFLOW_CONFIRM_TIMEOUT_MILLIS:5000}
  clean-nights-to-retire: ${ORDERFLOW_CLEAN_NIGHTS_TO_RETIRE:14}
```

`main.banner-mode: off` hides the logo; `web-application-type: none` means no web server. `logging.level` sets how chatty each logger is (`WARN` for the AMQP and SSH libraries). The `orderflow:` block is bound to the typed settings class (see the Bean Validation note): the SFTP address and credentials, the local inbox, the poll interval (5 seconds), the confirmation timeout (5,000 ms) and the number of clean nights needed to retire the legacy path (14).

## Try it yourself

1. Start the health command: `make health` (after `make up db`). Expect `ok database, broker and sftp folder reachable (0 file(s))` and exit code 0.
2. See the exit code of an unknown command: `java -jar target/replace-a-file-drop-0.1.0.jar --command=nope; echo $?` prints `unknown command` and `2`.
3. Override a setting three ways: `ORDERFLOW_POLL_SECONDS=1 make status`, `java -jar target/replace-a-file-drop-0.1.0.jar --command=status --orderflow.poll-seconds=1`, and by editing `application.yml`.
4. Trigger validation: `java -jar target/replace-a-file-drop-0.1.0.jar --command=health --orderflow.poll-seconds=0` stops at startup, naming `orderflow.pollSeconds`.
5. List the beans created: add `--debug` to a command and read the "positive matches" of auto-configuration in the output.

## Mistakes people make

- **Putting the setting in the wrong place.** A value in `application.yml` is overridden by an environment variable, which is overridden by a command-line argument. A test that passes settings as "default properties" is silently overridden by the file; pass them as arguments instead.
- **Two beans of the same type.** When Spring finds two candidates for one parameter it fails or picks by parameter name. Name parameters like the bean (`orderMemory`, `fileMemory`) or use a qualifier.
- **Asking for a bean by its concrete class when the declaration is an interface.** Spring Boot declares its admin bean as `AmqpAdmin`; asking for `RabbitAdmin` finds nothing. Ask for the interface.
- **A program that exits at once.** A command-line runner that returns ends the program, which is wanted for a tool and wrong for a service. A service must have something that keeps the process alive (a listener, or a blocked main thread).
- **Business logic inside `@Configuration` methods.** Configuration should only wire objects together; logic belongs in plain classes that can be tested without Spring.

Specific to this repository: a tool command that starts the flow profile by accident would start polling the SFTP folder; the services are started only with a profile, never with `--command`.

## Check yourself

1. What is a composition root? *The single class where the parts of the program are plugged together.*
2. Which wins: an environment variable or `application.yml`? *The environment variable.*
3. How does one jar become the flow, the order service or a tool? *The Spring profile (`flow`, `order-service`) or `--command=` decides which beans and runner are active.*
4. Why does `main` call `Thread.currentThread().join()` for the services? *To keep the process alive while the background threads work.*
5. Why does the runner print results to standard output and the log to standard error? *So a script can capture the report without log lines.*

## Further reading

- Spring Boot reference documentation, version 3.5: https://docs.spring.io/spring-boot/3.5/reference/
- Externalized configuration (the order settings are read): https://docs.spring.io/spring-boot/3.5/reference/features/external-config.html
- Spring Framework: the IoC container and beans: https://docs.spring.io/spring-framework/reference/core/beans.html
- Application exit and exit codes: https://docs.spring.io/spring-boot/3.5/reference/features/spring-application.html#features.spring-application.application-exit
