package com.xammer.cloud.autospotting.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Architecture rules for the AutoSpotting v2 program (plan-phase-03.md task 3.4, design-ux-ops.md
 * §9.1). Later phases append rules (controller return types, ReadOnlyCloudView, job-lease wrappers,
 * ledger call analysis). Phase 03 pins three:
 *
 * <ol>
 *   <li>v2 entities live only in {@code ..domain.autospotting..};</li>
 *   <li>{@code SpotGroup} is {@code @org.hibernate.annotations.Immutable};</li>
 *   <li>nothing outside {@code ..repository.autospotting..} and {@code ..service.autospotting.core..}
 *       depends on the spine repositories.</li>
 * </ol>
 */
class AutoSpottingArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.xammer.cloud");
    }

    @Test
    void v2EntitiesLiveOnlyInTheAutoSpottingDomainPackage() {
        ArchRule rule = classes()
                .that().areAnnotatedWith(jakarta.persistence.Entity.class)
                .and().resideInAPackage("..autospotting..")
                .should().resideInAPackage("..domain.autospotting..")
                .because("v2 entities belong in B/domain/autospotting (plan-phase-03.md §3.2)");
        rule.check(classes);
    }

    @Test
    void spotGroupIsImmutable() {
        ArchRule rule = classes()
                .that().haveSimpleName("SpotGroup")
                .should().beAnnotatedWith(org.hibernate.annotations.Immutable.class)
                .because("a stale SpotGroup entity must never write back lease or runtime columns "
                        + "(design-core.md §2.4)");
        rule.check(classes);
    }

    @Test
    void onlyTheSpineAndCoreServicesDependOnTheSpineRepositories() {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackages("..repository.autospotting..", "..service.autospotting.core..")
                .should().dependOnClassesThat().resideInAPackage("..repository.autospotting..")
                .because("the spine repositories are driven only by the core services "
                        + "(design-ux-ops.md §9.1)");
        rule.check(classes);
    }
}
