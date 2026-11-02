# Bean Validation (Jakarta Validation)

## What it is

Bean Validation is a standard way to state rules about the fields of an object with annotations and have them checked by a library: `@NotNull` (a value must be present), `@Min(1)` (a number must be at least 1), `@NotBlank` (text must contain something other than spaces) and many more. The checking engine is Hibernate Validator 8, an implementation of Jakarta Validation 3. "Bean" just means an ordinary Java object.

## Why this repository uses it

The flow's settings (where the SFTP server is, how often to poll, how long to wait for the broker's confirmation, how many clean nights retire the old path) come from outside the program. A poll interval of zero or an empty host name would make the flow misbehave in confusing ways. Rules on the settings object stop the program at startup with a clear message.

## Where it appears

| Path | What it does |
| --- | --- |
| `${P}` | Annotates each setting with `@Min`, `@NotNull` or `@NotBlank` and the class with `@Validated`. |
| `pom.xml` | Declares `spring-boot-starter-validation`. |

## How it works underneath

An annotation like `@Min(1)` does nothing by itself. A **validator** reads the annotations of an object through reflection and returns a set of **violations**. Spring Boot does this automatically for a `@ConfigurationProperties` class marked `@Validated`: after binding the settings it validates the object and refuses to start the application if there are violations. `@Valid` on a nested object tells the validator to look inside it too.

## Walkthrough of this repository's use

```java
package plainround.orderflow.infrastructure;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

```

Lines 3 to 6 import the annotations from `jakarta.validation.constraints` (the package name changed from `javax` to `jakarta` in Spring Boot 3). `@DefaultValue` and `@Validated` are Spring's; `Path` is a file location.

```java
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
```

The Javadoc lists each setting. `@Validated` switches validation on. `@ConfigurationProperties("orderflow")` binds settings that start with `orderflow.` (the `orderflow:` block of `application.yml`). The component list is a **record**, so the settings object is immutable. `@Valid @NotNull Sftp sftp` requires the SFTP block to exist and be checked. `@DefaultValue("data/inbox") @NotNull Path inbox` is the folder where the SFTP adapter keeps a local copy while a file is processed. `@DefaultValue("5") @Min(1) int pollSeconds` is the seconds between looks at the SFTP folder (at least 1). `@DefaultValue("5000") @Min(100) long confirmTimeoutMillis` is how long a publish waits for the broker's confirmation before it counts as failed (at least 100 milliseconds). `@DefaultValue("14") @Min(1) int cleanNightsToRetire` is the retirement rule (at least one night).

```java
  /** The partner's SFTP folder. */
  public record Sftp(
      @DefaultValue("localhost") @NotBlank String host,
      @DefaultValue("2222") @Min(1) int port,
      @DefaultValue("partner") @NotBlank String user,
      @DefaultValue("") String password,
      @DefaultValue("upload") @NotBlank String remoteDirectory) {}
}
```

The nested record `Sftp` holds the partner's server: `host` defaults to `localhost` and may not be blank, `port` defaults to 2222 (the application file overrides it for the compose stack) and must be at least 1, `user` defaults to `partner` and may not be blank, `password` defaults to empty text (the real value comes from the environment), and `remoteDirectory` defaults to `upload` and may not be blank.

## Try it yourself

1. Trigger a startup failure: `java -jar target/replace-a-file-drop-0.1.0.jar --command=health --orderflow.poll-seconds=0`. The message names `pollSeconds` and says it must be at least 1.
2. Refuse a blank host: `java -jar target/replace-a-file-drop-0.1.0.jar --command=health --orderflow.sftp.host=` fails the `@NotBlank` rule.
3. See the binding: `ORDERFLOW_CLEAN_NIGHTS_TO_RETIRE=2 make reconcile` changes how many clean nights the verdict needs.
4. Find the engine: `./mvnw dependency:tree | grep hibernate-validator`.
5. List the other places settings are bound: `grep -rn "ConfigurationProperties" src/main`.

## Mistakes people make

- **Forgetting `@Validated`.** The annotations are then ignored.
- **Forgetting `@Valid` on a nested record.** The rules inside `Sftp` would not be checked.
- **Validating late.** A check inside the flow runs after hours of work; a check at startup fails in a second.

## Check yourself

1. What does `@Min(1)` do? *Rejects values below 1.*
2. Who runs the checks? *A validator (Hibernate Validator); Spring Boot calls it for `@Validated` settings.*
3. What is the difference between `@NotNull` and `@NotBlank`? *`@NotNull` rejects `null`; `@NotBlank` rejects `null`, empty text and text of only spaces.*
4. What changed from `javax` to `jakarta`? *The package name, in Spring Boot 3.*
5. When is validation done? *At startup, after the settings are bound.*

## Further reading

- Jakarta Bean Validation 3.0: https://jakarta.ee/specifications/bean-validation/3.0/
- Hibernate Validator 8 reference: https://docs.jboss.org/hibernate/validator/8.0/reference/en-US/html_single/
- Spring Boot validated configuration properties: https://docs.spring.io/spring-boot/3.5/reference/features/external-config.html#features.external-config.typesafe-configuration-properties.validation
