# OSS Index audit

## What it is

Libraries have bugs, and some bugs are **vulnerabilities**: ways an attacker can misuse the program. People publish them in databases with identifiers called CVEs. **Sonatype OSS Index** is a free online database of known vulnerabilities for open-source libraries. The **OSS Index Maven plug-in** takes the dependencies of the project, asks the database about each one, and reports (and, with `<fail>true</fail>`, fails the build for) every known vulnerability. This is the "audit" step the delivery rules require.

## Why this repository uses it

The delivery rules require a dependency audit in continuous integration. For a Java project without a paid scanner, OSS Index is a free source of advisories.

## Where it appears

| Path | What it does with the audit |
| --- | --- |
| `pom.xml` lines 245 to 254 | Configures the plug-in. |
| `Makefile` | The `audit` target runs it. |
| `.github/workflows/ci.yml` | Runs `make audit` on every push. |

## How it works underneath

The plug-in lists the project's dependencies for the chosen scopes (`compile` and `runtime`: the code that ships), turns each into a **package URL** such as `pkg:maven/org.postgresql/postgresql@42.7.7`, sends the list to the OSS Index web service over HTTPS, and prints the vulnerabilities in the answer. It needs the internet. The result depends on the database at the moment you run it, so a build that passed last month can fail today when a new vulnerability is published; that is the point.

## Walkthrough of this repository's use

```xml
      <!-- The audit: checks every dependency against the OSS Index vulnerability database. -->
      <plugin>
        <groupId>org.sonatype.ossindex.maven</groupId>
        <artifactId>ossindex-maven-plugin</artifactId>
        <version>${ossindex.version}</version>
        <configuration>
          <scope>compile,runtime</scope>
          <fail>true</fail>
        </configuration>
      </plugin>
```

The XML comment on line 245 says what it is for. `<groupId>org.sonatype.ossindex.maven</groupId>` and `<artifactId>ossindex-maven-plugin</artifactId>` name the plug-in; `<version>` is the property 3.2.0. `<scope>compile,runtime</scope>` restricts the audit to dependencies that ship. `<fail>true</fail>` makes any reported vulnerability fail the build. It is run explicitly as `./mvnw org.sonatype.ossindex.maven:ossindex-maven-plugin:audit` because it is not bound to a phase.

## Try it yourself

1. Run it: `make audit`. Expect the build to finish successfully when nothing is reported.
2. See the exact command: `make -n audit`.
3. Read the plug-in's help: `./mvnw org.sonatype.ossindex.maven:ossindex-maven-plugin:help -Ddetail`.
4. See what it audits: `./mvnw dependency:tree -Dscope=runtime | head -30`.
5. Think offline: disconnect from the network and run step 1; it fails with a connection error, not with a vulnerability.

## Mistakes people make

- **Ignoring a failure because "it passed yesterday".** New advisories appear all the time; read the report and upgrade or document an exception.
- **Auditing test-only libraries as if they shipped.** The scope setting keeps the check on what ships.
- **Running offline.** The audit then cannot run; CI runs it on every push.

## Check yourself

1. What does the plug-in compare against? *The OSS Index database of known vulnerabilities.*
2. Why can the result change without code changes? *New advisories are published over time.*
3. What does `<fail>true</fail>` do? *Fails the build on any vulnerability.*
4. Which scopes are audited? *`compile` and `runtime`.*
5. Where does it run automatically? *In CI via `make audit`.*

## Further reading

- OSS Index Maven plug-in (3.2.0): https://sonatype.github.io/ossindex-maven/maven-plugin/
- OSS Index: https://ossindex.sonatype.org/
- Package URL specification: https://github.com/package-url/purl-spec
