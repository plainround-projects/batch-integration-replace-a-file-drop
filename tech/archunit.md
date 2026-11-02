# ArchUnit

## What it is

ArchUnit is a Java library that tests the **shape of the code** itself. Instead of calling a method and checking an answer, an ArchUnit test reads the compiled classes and checks rules such as "no class in the `domain` package may use anything from Spring" or "the `interfaces` package may not be used by any other package". It turns the architecture diagram into a test that fails the build the moment someone breaks the rule.

## Why this repository uses it

The project's design rule is the **dependency rule**: the domain knows nothing, the application knows the domain, the infrastructure knows both, and nothing may depend on the interfaces. Comments cannot keep a team to that. This test does.

## Where it appears

| Path | What it does with ArchUnit |
| --- | --- |
| `${T}/architecture/ArchitectureTest.java` | Three rules: the layers, no framework in the inner layers, no package cycles. |
| `pom.xml` | Pins `com.tngtech.archunit:archunit-junit5` 1.4.1. |

## How it works underneath

`@AnalyzeClasses(packages = "...")` tells ArchUnit which compiled classes to import (it reads the `.class` files and builds a model of who depends on whom: a class depends on another if it calls it, extends it, has a field of its type, or takes it as a parameter). Each field annotated `@ArchTest` of type `ArchRule` is one rule that the JUnit extension evaluates against that model. Rules are written in a small fluent language: `noClasses().that().resideInAnyPackage(...).should().dependOnClassesThat().resideInAnyPackage(...)`. The `..domain..` pattern means "the package `domain` at any depth under any prefix".

## Walkthrough of this repository's use

```java
package plainround.orderflow.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;

/**
 * The dependency rule, enforced: domain depends on nothing; application on domain; nothing depends
 * on interfaces.
 */
@AnalyzeClasses(
    packages = "plainround.orderflow",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

```

Lines 3 and 4 are static imports of the fluent builders. Lines 6 to 10 import the ArchUnit types. The Javadoc states the rule in words. `@AnalyzeClasses(packages = "plainround.orderflow", importOptions = ImportOption.DoNotIncludeTests.class)` (lines 16 to 18) says: look at the classes under this package, but ignore the test classes.

```java
  @ArchTest
  static final ArchRule layers =
      layeredArchitecture()
          .consideringOnlyDependenciesInLayers()
          .layer("domain")
          .definedBy("..domain..")
          .layer("application")
          .definedBy("..application..")
          .layer("infrastructure")
          .definedBy("..infrastructure..")
          .layer("interfaces")
          .definedBy("..interfaces..")
          .whereLayer("interfaces")
          .mayNotBeAccessedByAnyLayer()
          .whereLayer("infrastructure")
          .mayOnlyBeAccessedByLayers("interfaces")
          .whereLayer("application")
          .mayOnlyBeAccessedByLayers("infrastructure", "interfaces")
          .whereLayer("domain")
          .mayOnlyBeAccessedByLayers("application", "infrastructure", "interfaces");

```

The first rule is a field annotated `@ArchTest`. `layeredArchitecture()` starts a layered-architecture rule. `consideringOnlyDependenciesInLayers()` limits the check to the four layers named next (dependencies on the JDK and libraries are not layer-to-layer dependencies). Each `.layer("x").definedBy("..x..")` names a layer and the packages that belong to it. Then four `whereLayer` statements state who may use whom: **interfaces** may not be used by any layer; **infrastructure** may be used only by interfaces; **application** only by infrastructure and interfaces; **domain** by application, infrastructure and interfaces. Together they say that arrows point inward only.

```java
  @ArchTest
  static final ArchRule domain_and_application_know_no_framework =
      noClasses()
          .that()
          .resideInAnyPackage("..domain..", "..application..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("org.springframework..", "javax.sql..", "java.sql..");

  @ArchTest
  static final ArchRule no_cycles =
      SlicesRuleDefinition.slices()
          .matching("plainround.orderflow.(*)..")
          .should()
          .beFreeOfCycles();
}
```

The second rule says no class in `domain` or `application` may depend on anything in `org.springframework..`, `javax.sql..` or `java.sql..`: the inner layers cannot know the framework or the database. The third rule uses `SlicesRuleDefinition.slices().matching("plainround.orderflow.(*)..")` to treat each sub-package (domain, application, ...) as a slice and asserts that there are no cycles between slices (A uses B uses A).

## Try it yourself

1. Run it: `./mvnw -q test -Dtest=ArchitectureTest`. Quiet output means all three rules hold.
2. Break the rule: in `domain/OrderParser.java` add `import org.springframework.stereotype.Component;` and a use of it (for example annotate the class `@Component`), then run step 1. It fails and names the offending class. Undo it.
3. Break the layer rule: in `application/ProcessOrderMessage.java` import `plainround.orderflow.interfaces.OrderflowRunner` and mention it in a field; the layered rule fails.
4. See the rules fail on an empty match: change `"..domain.."` to `"..domane.."` in the first rule; ArchUnit reports that the rule matched no classes.
5. List the layer folders: `find src/main/java -maxdepth 4 -type d`.

## Mistakes people make

- **A rule that matches nothing.** If the package pattern is wrong the rule passes because there is nothing to check. ArchUnit fails such a rule by default ("failOnEmptyShould"), which is the safety net here.
- **Counting test classes.** The rules must ignore tests (`ImportOption.DoNotIncludeTests`), since tests legitimately touch every layer.
- **Allowing "just one exception".** Each exception erodes the rule; fix the code or change the rule on purpose.

## Check yourself

1. What does ArchUnit read? *The compiled classes, to know which class uses which.*
2. Which layer may nothing depend on? *Interfaces.*
3. Why exclude test classes? *Tests legitimately use every layer.*
4. What does the "no cycles" rule prevent? *Two packages depending on each other, which makes them impossible to separate.*
5. Where does the rule "no Spring in domain" live? *As an `@ArchTest` field in `ArchitectureTest`.*

## Further reading

- ArchUnit user guide (version 1.4.1, pinned in `pom.xml`): https://www.archunit.org/userguide/html/000_Index.html
- Layered architecture rules: https://www.archunit.org/userguide/html/000_Index.html#_layered_architecture
