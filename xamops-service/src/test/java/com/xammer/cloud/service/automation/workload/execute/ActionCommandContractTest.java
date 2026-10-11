package com.xammer.cloud.service.automation.workload.execute;

import com.xammer.cloud.service.automation.workload.ApplyMethod;
import com.xammer.cloud.service.automation.workload.ChangeKind;
import com.xammer.cloud.service.automation.workload.ContainerDecision;
import com.xammer.cloud.service.automation.workload.ContainerReason;
import com.xammer.cloud.service.automation.workload.ProductionClassScan;
import com.xammer.cloud.service.automation.workload.QosClass;
import com.xammer.cloud.service.automation.workload.ResourceEnvelope;
import com.xammer.cloud.service.automation.workload.WorkloadRef;
import com.xammer.cloud.service.automation.workload.access.ClusterTarget;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The executor's command type is the queue's schema, so its invariants are enforced at
 * construction: an unsatisfiable or un-replayable command must never become durable.
 *
 * <p>Also asserts <strong>which</strong> {@link WorkloadActionExecutor} implementations exist. This
 * assertion was an absence check while none shipped; it is now an exact list, so adding a second
 * thing that can write to a cluster stays a deliberate, reviewable act rather than an accident.
 */
class ActionCommandContractTest {

    private static final ClusterTarget TARGET =
            new ClusterTarget(7L, 42L, "102227938259", "xamops-test-cluster", "ap-south-1");
    private static final WorkloadRef REF = WorkloadRef.of("team-a", "Deployment", "overprovisioned-api");

    // ------------------------------------------------------------------ ActionCommand

    @Test
    void aWellFormedCommandIsAccepted() {
        ActionCommand command = command(Instant.now().plus(Duration.ofMinutes(10)));

        assertThat(command.correlationId()).isEqualTo("corr-1");
        assertThat(command.dryRun()).isTrue();
        assertThat(command.containers()).hasSize(1);
        assertThat(command.isExpiredAt(Instant.now())).isFalse();
    }

    @Test
    void aBlankCorrelationIdIsRejected() {
        // The correlation id is the idempotency key for at-least-once delivery. Without it, a
        // re-delivered command becomes a second patch.
        assertThatThrownBy(() -> new ActionCommand(" ", TARGET, REF, targets(), ApplyMethod.IN_PLACE,
                true, null, Instant.now().plusSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idempotency key");

        assertThatThrownBy(() -> new ActionCommand(null, TARGET, REF, targets(), ApplyMethod.IN_PLACE,
                true, null, Instant.now().plusSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aMissingExpiryIsRejected() {
        assertThatThrownBy(() -> new ActionCommand("corr-1", TARGET, REF, targets(), ApplyMethod.IN_PLACE,
                true, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stale measurements");
    }

    @Test
    void anExpiryInThePastIsAlreadyExpiredAndMustNotBeApplied() {
        ActionCommand expired = command(Instant.now().minus(Duration.ofMinutes(1)));

        assertThat(expired.isExpiredAt(Instant.now()))
                .as("a command whose window has passed must expire, not apply late")
                .isTrue();
    }

    @Test
    void anEmptyContainerListIsRejected() {
        assertThatThrownBy(() -> new ActionCommand("corr-1", TARGET, REF, List.of(), ApplyMethod.IN_PLACE,
                true, null, Instant.now().plusSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no-op");
    }

    @Test
    void duplicateContainerTargetsAreRejected() {
        // One metric applied to several containers is audit bug 8. Two targets for the same
        // container is the same mistake arriving from the other direction.
        List<ContainerApplyTarget> duplicated = List.of(
                new ContainerApplyTarget("api", 25, 25, 262144000L, 262144000L),
                new ContainerApplyTarget("api", 50, 50, 262144000L, 262144000L));

        assertThatThrownBy(() -> new ActionCommand("corr-1", TARGET, REF, duplicated, ApplyMethod.IN_PLACE,
                true, null, Instant.now().plusSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate container targets");
    }

    @Test
    void theContainerListIsDefensivelyCopied() {
        List<ContainerApplyTarget> mutable = new java.util.ArrayList<>(targets());
        ActionCommand command = new ActionCommand("corr-1", TARGET, REF, mutable, ApplyMethod.IN_PLACE,
                true, null, Instant.now().plusSeconds(60));

        mutable.clear();

        assertThat(command.containers()).hasSize(1);
        assertThatThrownBy(() -> command.containers().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    // ------------------------------------------------------------------ ContainerApplyTarget

    @Test
    void aRequestAboveItsOwnLimitIsRejected() {
        // The in-place resize subresource refuses this, so it must not reach the queue.
        assertThatThrownBy(() -> new ContainerApplyTarget("api", 500, 100, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds its limit");

        assertThatThrownBy(() -> new ContainerApplyTarget("api", null, null, 2_000_000L, 1_000_000L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds its limit");
    }

    @Test
    void aZeroOrNegativeRequestIsRejected() {
        // The measured Go output recommended a 0m CPU request on two workloads and a 1Mi memory
        // request on five. Zero is not a resource value this engine can express.
        assertThatThrownBy(() -> new ContainerApplyTarget("api", 0, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be positive");

        assertThatThrownBy(() -> new ContainerApplyTarget("api", null, null, -1L, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aTargetThatChangesNothingIsRejected() {
        assertThatThrownBy(() -> new ContainerApplyTarget("api", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nothing to apply");
    }

    @Test
    void aBlankContainerNameIsRejected() {
        assertThatThrownBy(() -> new ContainerApplyTarget(" ", 25, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one target per container");
    }

    @Test
    void movingOnlyOneResourceIsAllowed() {
        // Legitimate: an HPA may freeze CPU while memory stays eligible.
        ContainerApplyTarget memoryOnly = new ContainerApplyTarget("api", null, null, 262144000L, 262144000L);

        assertThat(memoryOnly.cpuRequestMillis()).isNull();
        assertThat(memoryOnly.memRequestBytes()).isEqualTo(262144000L);
    }

    // ------------------------------------------------------------------ decision -> command

    @Test
    void aFrozenFieldIsDiffedOutRatherThanPatchedWithItsOwnValue() {
        // The engine froze CPU on this container (an HPA's utilisation denominator) and moved memory.
        // `recommended` is a complete envelope, so its CPU request equals the current one -- which a
        // wholesale copy would turn into a patch of a request the engine refused to decide.
        ContainerDecision hpaManaged = new ContainerDecision("php-apache", QosClass.BURSTABLE,
                new ResourceEnvelope(200, null, 67_108_864L, null),
                ResourceEnvelope.recommendation(new ResourceEnvelope(200, null, 67_108_864L, null),
                        200, null, 524_288_000L, null),
                List.of(ContainerReason.HPA_CPU_MANAGED), ChangeKind.INCREASE);

        ContainerApplyTarget target = ContainerApplyTarget.ofChanges(hpaManaged).orElseThrow();

        assertThat(target.cpuRequestMillis())
                .as("frozen by HPA_CPU_MANAGED: null means leave it alone, which is what the "
                        + "engine decided")
                .isNull();
        assertThat(target.cpuLimitMillis()).isNull();
        assertThat(target.memRequestBytes()).isEqualTo(524_288_000L);
    }

    @Test
    void everyDecidedFieldSurvivesTheDiff() {
        ResourceEnvelope current = new ResourceEnvelope(1000, 1000, 1_073_741_824L, 1_073_741_824L);
        ContainerDecision flooredDecrease = new ContainerDecision("api", QosClass.GUARANTEED, current,
                ResourceEnvelope.recommendation(current, 25, 25, 262_144_000L, 262_144_000L),
                List.of(ContainerReason.FLOOR_CLAMPED), ChangeKind.DECREASE);

        ContainerApplyTarget target = ContainerApplyTarget.ofChanges(flooredDecrease).orElseThrow();

        assertThat(target.cpuRequestMillis()).isEqualTo(25);
        assertThat(target.cpuLimitMillis()).isEqualTo(25);
        assertThat(target.memRequestBytes()).isEqualTo(262_144_000L);
        assertThat(target.memLimitBytes()).isEqualTo(262_144_000L);
    }

    @Test
    void noRecommendationYieldsNoCommandAtAll() {
        ResourceEnvelope current = new ResourceEnvelope(80, null, 67_108_864L, null);
        ContainerDecision noChange = new ContainerDecision("worker", QosClass.BURSTABLE, current, null,
                List.of(ContainerReason.WITHIN_HYSTERESIS), ChangeKind.NONE);

        assertThat(ContainerApplyTarget.ofChanges(noChange))
                .as("nothing to apply must be an empty result, not a command that patches nothing")
                .isEmpty();
    }

    // ------------------------------------------------------------------ submission and outcome

    @Test
    void aRefusalMustSayWhy() {
        assertThatThrownBy(() -> ActionSubmission.refused("corr-1", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must say why");

        assertThat(ActionSubmission.accepted("corr-1").state()).isEqualTo(SubmitState.ACCEPTED);
        assertThat(ActionSubmission.duplicate("corr-1").state()).isEqualTo(SubmitState.DUPLICATE);
    }

    @Test
    void anOutcomeCarriesDryRunAndA1BasedAttempt() {
        ActionOutcome outcome = new ActionOutcome("corr-1", true, ActionOutcome.Terminal.APPLIED,
                java.util.Map.of("api", new ActionOutcome.ContainerApplyStatus("api", true, null)),
                Instant.now(), 1, null);

        assertThat(outcome.dryRun()).isTrue();
        assertThat(outcome.perContainer()).containsKey("api");

        assertThatThrownBy(() -> new ActionOutcome("corr-1", false, ActionOutcome.Terminal.APPLIED,
                java.util.Map.of(), Instant.now(), 0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1-based");
    }

    @Test
    void expiryIsATerminalState() {
        // A command that sat in the queue past its window has a name for that, so an audit entry
        // never has to imply it was applied.
        assertThat(ActionOutcome.Terminal.values()).contains(ActionOutcome.Terminal.EXPIRED);
    }

    // ------------------------------------------------------------------ which executors exist

    @Test
    void exactlyOneWorkloadActionExecutorIsImplementedAndItIsTheDirectInPlaceOne() {
        List<String> implementations = ProductionClassScan.implementationsOf(WorkloadActionExecutor.class);

        // This assertion used to require the list to be EMPTY: with no implementation, the absence
        // of a collaborator -- rather than a flag check that could be mis-evaluated -- was what made
        // it impossible for this code to change a cluster. One ships now, so the invariant becomes
        // "exactly this one". Adding another is still a deliberate, reviewable act: it has to come
        // through this test, and anything that can write to a cluster has to justify itself here.
        assertThat(implementations)
                .as("exactly one executor ships, and it is the in-place pod resize one")
                .containsExactly(
                        "com.xammer.cloud.service.automation.workload.execute.DirectInPlaceResizeExecutor");
    }

    // ------------------------------------------------------------------ helpers

    private static List<ContainerApplyTarget> targets() {
        return List.of(new ContainerApplyTarget("api", 25, 25, 262144000L, 262144000L));
    }

    private static ActionCommand command(Instant notValidAfter) {
        return new ActionCommand("corr-1", TARGET, REF, targets(), ApplyMethod.IN_PLACE,
                true, "observed-7", notValidAfter);
    }
}
