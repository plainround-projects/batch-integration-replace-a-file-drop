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
