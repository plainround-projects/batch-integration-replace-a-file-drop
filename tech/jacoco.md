# JaCoCo

## What it is

JaCoCo (Java Code Coverage) measures **which lines of the code ran** while the tests ran. If a method is never executed by any test, JaCoCo reports it as uncovered. **Coverage** is the share of lines that ran. A high number does not prove the tests are good, but a low number proves some code is never tested.

## Why this repository uses it

The delivery rules require the domain and the application layers to be at least 95 percent covered. The check turns that rule into a failing build instead of a promise.

## Where it appears

| Path | What it does with JaCoCo |
| --- | --- |
| `pom.xml` lines 184 to 228 | The plug-in with three executions: prepare the agent, write the report, check the limit. |
| `pom.xml` lines 160 and 172 | `@{argLine}` makes Surefire and Failsafe use the agent. |
| `lombok.config` | Marks generated code so it is not counted. |

## How it works underneath

JaCoCo works as a **Java agent**: a small program attached to the JVM when it starts (`-javaagent:...`). The agent rewrites each class as it is loaded so that it records which lines run, and writes a file (`target/jacoco.exec`) when the JVM ends. The Maven plug-in has goals: `prepare-agent` (adds the `-javaagent` option to the test JVM through the property `argLine`), `report` (turns the recording into an HTML report under `target/site/jacoco/`) and `check` (compares coverage with limits and fails the build if too low). That is why the Surefire setting is `@{argLine} -XX:+EnableDynamicAgentLoading`: `@{argLine}` is a late-bound reference to whatever JaCoCo put in `argLine`, and the extra flag allows agents to attach on newer JDKs.

## Walkthrough of this repository's use

```xml
      <plugin>
        <groupId>org.jacoco</groupId>
        <artifactId>jacoco-maven-plugin</artifactId>
        <version>${jacoco.version}</version>
        <executions>
          <execution>
            <id>prepare-agent</id>
            <goals>
              <goal>prepare-agent</goal>
            </goals>
          </execution>
          <execution>
            <id>report</id>
            <phase>test</phase>
            <goals>
              <goal>report</goal>
            </goals>
          </execution>
```

A plug-in with `<executions>`: each `<execution>` has an `<id>`, a `<phase>` (when it runs) and `<goals>`. The first, `prepare-agent`, has no phase listed because the goal has a default (`initialize`): it sets the `argLine` property before the tests start. The second, `report`, is bound to the `test` phase and writes `target/site/jacoco/index.html`.

```xml
          <execution>
            <id>check</id>
            <phase>test</phase>
            <goals>
              <goal>check</goal>
            </goals>
            <configuration>
              <rules>
                <rule>
                  <element>BUNDLE</element>
                  <includes>
                    <include>plainround.orderflow.domain.*</include>
                    <include>plainround.orderflow.application.*</include>
                  </includes>
                  <limits>
                    <limit>
                      <counter>LINE</counter>
                      <value>COVEREDRATIO</value>
                      <minimum>0.95</minimum>
                    </limit>
                  </limits>
                </rule>
              </rules>
            </configuration>
          </execution>
        </executions>
      </plugin>
```

The `check` execution (also in the `test` phase) holds one `<rule>`: `<element>BUNDLE</element>` means "all the classes selected together"; the `<includes>` select only classes in `plainround.orderflow.domain` and `plainround.orderflow.application`; the `<limit>` says the `LINE` counter, measured as the `COVEREDRATIO` (covered lines divided by all lines), must be at least `0.95`. If it is lower, Maven prints "Rule violated for bundle" and stops with a failure.

```xml
      <!-- Unit tests run on every build; tests tagged integration or e2e need the services of docker-compose.yml. -->
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <configuration>
          <excludedGroups>integration,e2e</excludedGroups>
          <argLine>@{argLine} -XX:+EnableDynamicAgentLoading</argLine>
        </configuration>
      </plugin>
```

The Surefire configuration that makes the agent work: `@{argLine}` is replaced at run time with the option JaCoCo added.

## Try it yourself

1. Run the tests with coverage: `make test`. It ends with the build successful when the gate passes.
2. Open the report: `open target/site/jacoco/index.html`; click `plainround.orderflow.domain` and look at the green (covered) and red (missed) lines.
3. Read the numbers without a browser: `head -3 target/site/jacoco/jacoco.csv`.
4. Make the gate fail: delete one test from `DomainTest.java`, run `make test` and read "Rule violated". Undo it.
5. Check the agent is attached: `ls -l target/jacoco.exec` after a test run.

## Mistakes people make

- **Chasing 100 percent everywhere.** The gate here applies to `domain` and `application`, where the rules live; framework glue is covered by integration tests.
- **Counting generated code.** Lombok-generated methods would look uncovered; `lombok.addLombokGeneratedAnnotation = true` makes JaCoCo skip them.
- **Forgetting `argLine`.** If another plug-in overwrites `argLine`, the agent is not attached and the report is empty.

## Check yourself

1. What does the JaCoCo agent record? *Which lines of which classes ran.*
2. Which classes does the gate apply to? *Those in the domain and application packages.*
3. What does `@{argLine}` do? *Inserts the agent option JaCoCo placed in `argLine` into the test JVM options.*
4. In which phase do report and check run? *`test`.*
5. What is the limit? *Line coverage of at least 0.95.*

## Further reading

- JaCoCo Maven plug-in (0.8.13): https://www.jacoco.org/jacoco/trunk/doc/maven.html
- Check goal and rules: https://www.jacoco.org/jacoco/trunk/doc/check-mojo.html
