# Maven 3.9 and pom.xml

## What it is

Maven is a **build tool** for Java. A build is everything between "I have source files" and "I have a program I can run": download the libraries the code needs, compile the code, run the tests, package the result into one file. Maven does these steps from one description file, `pom.xml` (POM means Project Object Model). Instead of writing the steps, you declare *what the project is*, and Maven applies standard steps.

Terms:

- A **dependency** is a library your code uses. You name it by three values, its **coordinates**: `groupId` (who made it), `artifactId` (which library) and `version`. Maven downloads it (and the libraries *it* needs, its **transitive** dependencies) from a public server called **Maven Central** into a local cache folder, `~/.m2/repository`.
- A **plug-in** is a tool Maven runs: the compiler plug-in, the test plug-ins (Surefire, Failsafe), the packaging plug-in.
- A **phase** is a named step in the **lifecycle**: `validate`, `compile`, `test`, `package`, `verify`, `install`. Asking for a phase runs every phase before it, so `mvn package` also compiles and tests. A **goal** is one job of a plug-in, written `plugin:goal` (for example `failsafe:integration-test`). Plug-ins bind their goals to phases.
- A **scope** says when a dependency is needed: `compile` (default: always), `runtime` (only when the program runs), `provided` (needed to compile, supplied elsewhere or not needed at run time), `test` (only for tests).
- A **parent POM** is a POM you inherit from. This project inherits `spring-boot-starter-parent`, which pins the versions of hundreds of libraries so they work together, and sets sensible plug-in defaults. That is why most dependencies below have no `<version>`.

## Why this repository uses it

Maven builds the program, runs every test, formats and checks the code, measures coverage, audits dependencies and packages the executable jar. Every `make` target that touches Java ends in a `./mvnw` call, so this file is the single description of how the project is built.

## Where it appears

| Path | What it does with Maven |
| --- | --- |
| `pom.xml` | The project description: parent, properties, dependencies, plug-ins. |
| `mvnw`, `mvnw.cmd`, `.mvn/` | The Maven Wrapper (see its own note). |
| `Makefile` | Calls `./mvnw` for build, test, lint, audit. |
| `Dockerfile` | Builds the jar inside a container. |
| `.github/workflows/ci.yml` | Runs the same `make` targets in continuous integration. |

## How it works underneath

When you run `./mvnw package`, Maven reads `pom.xml`, merges it with the parent POM (the parent's settings apply unless the project overrides them), resolves the dependency tree (downloads missing files), and then runs the lifecycle phases in order. `compile` calls the compiler plug-in, which runs `javac` on `src/main/java` and writes `target/classes`. `test-compile` does the same for `src/test/java`. `test` runs Surefire. `package` runs the Spring Boot plug-in, which builds one **executable jar** (a zip file) in `target/` containing the compiled classes, the resources, and every dependency, plus a small launcher, so that `java -jar target/<name>.jar` starts the program. Everything Maven writes goes under `target/`, which is not committed to git.

Properties: `<properties>` defines named values; `${name}` uses one. This project keeps every version it pins (`archunit.version`, `jacoco.version`, ...) in properties so each appears once.

## Walkthrough of this repository's use

### The header and the parent

XML is a text format made of nested elements `<name>value</name>`. The file starts with a declaration, then the root element `<project>` with the XML namespace attributes that tell tools which schema describes the file.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.5.16</version>
    <relativePath/>
  </parent>

  <groupId>plainround</groupId>
  <artifactId>replace-a-file-drop</artifactId>
  <version>0.1.0</version>
  <name>replace-a-file-drop</name>
  <description>Replaces a partner's nightly file drop with a Spring Integration message flow: split, validate, deduplicate and publish with confirmations, an idempotent order service, old and new side by side, and a reconciliation report.</description>
```

`<modelVersion>4.0.0</modelVersion>` is the POM format version (always 4.0.0). The `<parent>` block (lines 6 to 11) inherits from `spring-boot-starter-parent` version 3.5.16: its dependency-management section pins compatible versions for Spring, Spring Integration, Spring AMQP, Hibernate Validator, JUnit, AssertJ, Awaitility, PostgreSQL's driver and many more; and it configures plug-ins (Java release, UTF-8, the Boot packaging). `<relativePath/>` (empty) says "look in the repository, not in a parent folder". Lines 13 to 17 are this project's own coordinates (`plainround:replace-a-file-drop:0.1.0`), a name and a description.

### Properties

```xml
  <properties>
    <!-- The code targets Java 21 (an LTS); it builds and runs on any newer JDK. -->
    <java.version>21</java.version>
    <maven.compiler.release>21</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <archunit.version>1.4.1</archunit.version>
    <instancio.version>5.5.1</instancio.version>
    <result4j.version>1.2.0</result4j.version>
    <spotless.version>2.44.5</spotless.version>
    <google-java-format.version>1.28.0</google-java-format.version>
    <jacoco.version>0.8.13</jacoco.version>
    <ossindex.version>3.2.0</ossindex.version>
    <failsafe.groups>integration,e2e</failsafe.groups>
  </properties>
```

`java.version` and `maven.compiler.release` are 21: the compiler produces Java 21 bytecode even on a newer JDK. `project.build.sourceEncoding` UTF-8 fixes the character encoding of source files. Then the versions of the libraries and plug-ins that Spring Boot does not manage: ArchUnit, Instancio, Result4J, Spotless, google-java-format, JaCoCo, OSS Index. `failsafe.groups` is the default list of test tags for integration runs; the Makefile overrides it from the command line with `-Dfailsafe.groups=e2e`.

### Dependencies

```xml
  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-integration</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-amqp</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-jdbc</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.integration</groupId>
      <artifactId>spring-integration-sftp</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.integration</groupId>
      <artifactId>spring-integration-amqp</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.integration</groupId>
      <artifactId>spring-integration-jdbc</artifactId>
    </dependency>
```

Each `<dependency>` is a coordinate (group, artifact, and a version unless the parent manages it). A **starter** is a dependency that brings a whole set of compatible libraries. `spring-boot-starter-integration` brings Spring Integration's core. `spring-boot-starter-amqp` brings Spring AMQP and the RabbitMQ client. `spring-boot-starter-jdbc` brings Spring's JDBC support and the HikariCP connection pool. `spring-integration-sftp` adds the SFTP adapters and Apache MINA SSHD, `spring-integration-amqp` the AMQP adapters, `spring-integration-jdbc` the JDBC metadata store (the table `INT_METADATA_STORE`).

```xml
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-json</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
    <dependency>
      <groupId>org.projectlombok</groupId>
      <artifactId>lombok</artifactId>
      <scope>provided</scope>
    </dependency>
    <dependency>
      <groupId>com.github.sviperll</groupId>
      <artifactId>result4j</artifactId>
      <version>${result4j.version}</version>
    </dependency>
    <dependency>
      <groupId>org.postgresql</groupId>
      <artifactId>postgresql</artifactId>
      <scope>runtime</scope>
    </dependency>
```

`spring-boot-starter-json` brings Jackson (the Jackson note). `spring-boot-starter-validation` brings Bean Validation. `lombok` has scope `provided` (needed to compile, not packaged). `result4j` has an explicit version from the property. `postgresql` (the JDBC driver) has scope `runtime`: the code never refers to its classes, but it must be present when the program runs.

```xml
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.integration</groupId>
      <artifactId>spring-integration-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.instancio</groupId>
      <artifactId>instancio-junit</artifactId>
      <version>${instancio.version}</version>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.awaitility</groupId>
      <artifactId>awaitility</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>com.tngtech.archunit</groupId>
      <artifactId>archunit-junit5</artifactId>
      <version>${archunit.version}</version>
      <scope>test</scope>
    </dependency>
  </dependencies>
```

Test dependencies, all with `<scope>test</scope>` so none is packaged: `spring-boot-starter-test` brings JUnit 5, AssertJ, Mockito and Spring's test support; `spring-integration-test` brings Spring Integration test helpers; `instancio-junit` (random test objects, declared but not used), `awaitility` (waits for asynchronous conditions in tests) and `archunit-junit5` (architecture tests).

### Resources

```xml
  <build>
    <resources>
      <resource>
        <directory>src/main/resources</directory>
      </resource>
      <!-- The reconciliation SQL is one set of files in sql/; the jar carries a copy so the program reads the same text. -->
      <resource>
        <directory>sql</directory>
        <targetPath>sql</targetPath>
        <includes>
          <include>reconcile.sql</include>
          <include>reconcile_details.sql</include>
        </includes>
      </resource>
    </resources>
```

By default Maven copies `src/main/resources` into the jar. This build adds a second resource: the folder `sql` is copied to `sql` inside the jar, but only `reconcile.sql` and `reconcile_details.sql` (`<includes>`). The comment says why: the reconciliation SQL is one set of files in `sql/` that a person can run with `psql`, and the jar carries a copy so the Java program reads exactly the same text (`ClassPathResource("sql/reconcile.sql")`). The Makefile's jar target lists `sql` among its dependencies, so changing a SQL file rebuilds the jar.

### Plug-ins

```xml
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>

      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <configuration>
          <parameters>true</parameters>
          <!-- Since JDK 23 annotation processing is off unless asked for; Lombok is the only processor. -->
          <annotationProcessorPaths>
            <path>
              <groupId>org.projectlombok</groupId>
              <artifactId>lombok</artifactId>
              <version>${lombok.version}</version>
            </path>
          </annotationProcessorPaths>
          <showWarnings>true</showWarnings>
          <compilerArgs>
            <arg>-Xlint:all,-processing,-serial</arg>
            <arg>-Werror</arg>
          </compilerArgs>
        </configuration>
      </plugin>
```

`<plugins>` lists the plug-ins. `spring-boot-maven-plugin` (no configuration) builds the executable jar during `package`. The compiler plug-in configuration: `<parameters>true</parameters>` keeps method parameter names in the bytecode (Spring can then match parameters by name); the `annotationProcessorPaths` block tells the compiler to run Lombok (see the Lombok note; `${lombok.version}` comes from the Boot parent); `showWarnings` prints compiler warnings; the `compilerArgs` turn on every lint check except `processing` and `serial` (`-Xlint:all,-processing,-serial`) and `-Werror`, which turns any warning into an error.

The remaining plug-ins are explained where they belong: Surefire and Failsafe in the JUnit note (lines 154 to 182), JaCoCo in its note (184 to 228), Spotless in its note (230 to 243) and the OSS Index audit in its note (245 to 254).

```xml
    </plugins>
  </build>
</project>
```

The closing tags: `</plugins>`, `</build>`, `</project>`. Every opening tag has a matching closing tag; a missing one makes Maven stop with a parse error.

## Try it yourself

1. Show the effective POM (the parent merged in): `./mvnw help:effective-pom -q | head -40`.
2. Show the dependency tree: `./mvnw dependency:tree | head -40`. You will see the AMQP client, the SSH library and Jackson among the dozens of libraries.
3. Compile only: `./mvnw -q compile`. Expect no output, and classes under `target/classes/`.
4. Package the jar: `./mvnw -q -DskipTests package` then `ls -l target/*.jar` and `java -jar target/replace-a-file-drop-0.1.0.jar --command=health`.
5. Make the compiler strict failure visible: add an unused cast to any class (for example `Object o = (Object) "x";`) and run `./mvnw -q compile`; `-Werror` turns the "redundant cast" warning into an error. Undo it.

## Mistakes people make

- **Running `mvn` instead of the wrapper.** A different Maven version on another machine can behave differently. `./mvnw` downloads the version the project names.
- **Version clashes.** Two libraries needing different versions of a third. The Spring Boot parent pins compatible versions; pin your own only when you must (the repository pins ArchUnit, Instancio and Result4J because Boot does not manage them).
- **Putting a test library in the default scope.** It would be packaged into the production jar. Test libraries here have `<scope>test</scope>`.
- **Committing `target/`.** It is rebuilt every time; the `.gitignore` excludes it.
- **Compiler warnings ignored.** This project compiles with `-Xlint:all -Werror`, so a warning stops the build.

## Check yourself

1. What is a dependency's scope `provided`? *Needed to compile, not packaged in the program.*
2. Why do most dependencies here have no version? *The Spring Boot parent manages them.*
3. Which phase runs the unit tests? *`test`.*
4. What does `-Werror` do? *Turns compiler warnings into errors, failing the build.*
5. Where does Maven write its output? *`target/`.*

## Further reading

- Maven documentation: https://maven.apache.org/guides/index.html
- POM reference: https://maven.apache.org/pom.html
- Build lifecycle: https://maven.apache.org/guides/introduction/introduction-to-the-lifecycle.html
- Spring Boot Maven plug-in 3.5: https://docs.spring.io/spring-boot/3.5/maven-plugin/
- Maven compiler plug-in: https://maven.apache.org/plugins/maven-compiler-plugin/
