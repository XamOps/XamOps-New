package com.xammer.cloud.autospotting.arch;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
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
 *
 * <p>Phase 04 adds a fourth (plan-phase-04.md task 4.2, design-ux-ops.md §9.1): every v2
 * {@code @Scheduled} method (one in {@code ..service.autospotting..}) must call
 * {@code JobRunner.runLeased}, so no scheduled AutoSpotting job runs without its job lease. There are
 * no v2 {@code @Scheduled} methods yet (the machinery ships dark), so the rule is satisfied vacuously
 * now and fails the first time a later phase adds one that skips the wrapper.
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

    @Test
    void noAutoSpottingClassLogsARawCloudSdkExceptionMessageWithoutTheRedactor() {
        // plan-phase-05.md task 5.3, design-core.md §8, AC-SEC-07: a cloud SDK error body can carry
        // credentials or tokens, so no class in ..service.autospotting.. may read a cloud SDK
        // exception's message accessor unless it also depends on AuditRedactor (the one component
        // that scrubs it). Vacuously satisfied now (no SDK exception accessors are called from the
        // v2 core yet) and fails the first class that logs a raw provider error unscrubbed.
        ArchRule rule = classes()
                .that().resideInAPackage("..service.autospotting..")
                .and(new DescribedPredicate<com.tngtech.archunit.core.domain.JavaClass>(
                        "call a cloud SDK exception message accessor") {
                    @Override
                    public boolean test(com.tngtech.archunit.core.domain.JavaClass javaClass) {
                        return javaClass.getMethodCallsFromSelf().stream().anyMatch(IS_CLOUD_SDK_EXCEPTION_MESSAGE);
                    }
                })
                .should().dependOnClassesThat().haveFullyQualifiedName(
                        "com.xammer.cloud.service.autospotting.core.audit.AuditRedactor")
                .because("a raw cloud SDK error body must be scrubbed by AuditRedactor before it is "
                        + "logged or stored (design-core.md §8, FR-SEC-06, AC-SEC-07)");
        rule.allowEmptyShould(true).check(classes);
    }

    @Test
    void noReadOnlyCloudViewDeclaresAMutationShapedMethod() {
        // plan-phase-06.md task 6.2, design-core.md §7.2/§9.1, AC-PLN-01 (structural part): a
        // ReadOnlyCloudView implementation exposes only Describe/List/Get, so no method it declares
        // may read like a mutation (update*, create*, delete*, put*, patch*, insert*, set*, start*,
        // terminate*). A mutating call then can't compile in planning. Vacuous until a provider's
        // read-only view ships (Phases 12/26) plus the test FakeSpotProvider.View.
        ArchRule rule = methods()
                .that().areDeclaredInClassesThat().areAssignableTo(
                        "com.xammer.cloud.service.autospotting.spi.ReadOnlyCloudView")
                .and().areDeclaredInClassesThat().areNotInterfaces()
                .should(notBeNamedLikeAMutation())
                .because("a ReadOnlyCloudView exposes only reads, so a mutating call can't compile in "
                        + "planning (design-core.md §7.2, AC-PLN-01)");
        rule.allowEmptyShould(true).check(classes);
    }

    @Test
    void everyV2ScheduledMethodRunsThroughTheJobLeaseWrapper() {
        ArchRule rule = methods()
                .that().areAnnotatedWith(org.springframework.scheduling.annotation.Scheduled.class)
                .and().areDeclaredInClassesThat().resideInAPackage("..service.autospotting..")
                .should(callJobRunnerRunLeased())
                .because("a v2 @Scheduled method must take its job lease via JobRunner.runLeased "
                        + "(design-core.md §3.5, design-ux-ops.md §9.1)");
        // allowEmptyShould: there are no v2 @Scheduled methods yet (the machinery ships dark).
        rule.allowEmptyShould(true).check(classes);
    }

    /**
     * A call to a cloud SDK exception's message/error-body accessor: the AWS SDK's
     * {@code SdkException#getMessage}/{@code AwsServiceException#awsErrorDetails}, or Google's
     * {@code GoogleJsonResponseException#getDetails}/{@code getContent}. These carry the raw provider
     * error body that must pass through {@link com.xammer.cloud.service.autospotting.core.audit.AuditRedactor}.
     */
    private static final DescribedPredicate<JavaCall<?>> IS_CLOUD_SDK_EXCEPTION_MESSAGE =
            new DescribedPredicate<>("a cloud SDK exception message accessor") {
                @Override
                public boolean test(JavaCall<?> call) {
                    String owner = call.getTargetOwner().getName();
                    String name = call.getName();
                    boolean awsException = owner.startsWith("software.amazon.awssdk")
                            && owner.endsWith("Exception");
                    boolean googleException = owner.equals("com.google.api.client.googleapis.json.GoogleJsonResponseException");
                    if (awsException) {
                        return name.equals("getMessage") || name.equals("awsErrorDetails");
                    }
                    if (googleException) {
                        return name.equals("getDetails") || name.equals("getContent") || name.equals("getMessage");
                    }
                    return false;
                }
            };

    /** The mutation-shaped method-name prefixes refused on a {@link com.xammer.cloud.service.autospotting.spi.ReadOnlyCloudView}. */
    private static final java.util.List<String> MUTATION_PREFIXES = java.util.List.of(
            "update", "create", "delete", "put", "patch", "insert", "set", "start", "terminate");

    private static ArchCondition<JavaMethod> notBeNamedLikeAMutation() {
        return new ArchCondition<>("not be named like a mutation") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                String name = method.getName().toLowerCase(java.util.Locale.ROOT);
                boolean mutationShaped = MUTATION_PREFIXES.stream().anyMatch(name::startsWith);
                events.add(new SimpleConditionEvent(method, !mutationShaped,
                        method.getFullName() + (mutationShaped ? " is named like a mutation" : " is read-shaped")));
            }
        };
    }

    private static ArchCondition<JavaMethod> callJobRunnerRunLeased() {
        DescribedPredicate<JavaCall<?>> isRunLeased = new DescribedPredicate<>("a call to JobRunner.runLeased") {
            @Override
            public boolean test(JavaCall<?> call) {
                return call.getTargetOwner().getName()
                        .equals("com.xammer.cloud.service.autospotting.core.JobRunner")
                        && call.getName().equals("runLeased");
            }
        };
        return new ArchCondition<>("call JobRunner.runLeased") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                boolean calls = method.getCallsFromSelf().stream().anyMatch(isRunLeased);
                events.add(new SimpleConditionEvent(method, calls,
                        method.getFullName() + (calls ? " calls" : " does not call") + " JobRunner.runLeased"));
            }
        };
    }
}
