package com.xammer.cloud.autospotting.fake;

import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.service.autospotting.spi.EligibilityResult;
import com.xammer.cloud.service.autospotting.spi.EvalMode;
import com.xammer.cloud.service.autospotting.spi.GroupRef;
import com.xammer.cloud.service.autospotting.spi.GroupStatus;
import com.xammer.cloud.service.autospotting.spi.OperationContext;
import com.xammer.cloud.service.autospotting.spi.PlanBody;
import com.xammer.cloud.service.autospotting.spi.PlanRequest;
import com.xammer.cloud.service.autospotting.spi.Profile;
import com.xammer.cloud.service.autospotting.spi.ReconcileAction;
import com.xammer.cloud.service.autospotting.spi.SnapshotPayload;
import com.xammer.cloud.service.autospotting.spi.StepContext;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;
import com.xammer.cloud.service.autospotting.spi.StepProgram;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@link FakeSpotProvider} honours the {@link com.xammer.cloud.service.autospotting.spi.SpotProvider}
 * contract (plan-phase-06.md task 6.2 verify): deterministic, read-only planning, a re-derivable step
 * program, and the deterministic {@code uuidv5(op, seq)} idempotency token. No cloud call is made.
 */
class FakeSpotProviderContractTest {

    private final FakeSpotProvider provider = new FakeSpotProvider(Provider.AWS);
    private final FakeSpotProvider.View view = new FakeSpotProvider.View();

    private static GroupRef ref() {
        return new GroupRef(Provider.AWS, 1L, "us-east-1/asg-fake", "arn:fake");
    }

    @Test
    void reportsItsProvider() {
        assertThat(provider.provider()).isEqualTo(Provider.AWS);
    }

    @Test
    void checkEligibilityIsDeterministicAndNeverThrows() {
        EligibilityResult a = provider.checkEligibility(ref(), view, new Profile("conservative", null), EvalMode.PLAN);
        EligibilityResult b = provider.checkEligibility(ref(), view, new Profile("conservative", null), EvalMode.LIST);
        assertThat(a.eligible()).isTrue();
        assertThat(a.blockers()).isEmpty();
        // Same rule codes and shape in every mode (AC-ELG-05): no blockers in either.
        assertThat(b.eligible()).isTrue();
    }

    @Test
    void createPlanFillsEveryFieldAndIsDeterministic() {
        PlanRequest req = new PlanRequest(ref(), OperationKind.ENABLE, new Profile("conservative", null));
        PlanBody one = provider.createPlan(req, view);
        PlanBody two = provider.createPlan(req, view);

        assertThat(one.currentJson()).isNotBlank();
        assertThat(one.targetJson()).isNotBlank();
        assertThat(one.snapshotToStore()).isNotNull();
        assertThat(one.rollout()).isNotNull();
        assertThat(one.estimates()).hasSize(1);
        assertThat(one.estimates().get(0).label()).isEqualTo(PlanBody.Estimate.LABEL);
        // Deterministic for the same inputs (§9.2).
        assertThat(two.targetJson()).isEqualTo(one.targetJson());
    }

    @Test
    void takeSnapshotIsExactAndDeterministic() {
        SnapshotPayload a = provider.takeSnapshot(ref(), view);
        SnapshotPayload b = provider.takeSnapshot(ref(), view);
        assertThat(a.exact()).isTrue();
        assertThat(a.payloadSha256()).isEqualTo(b.payloadSha256());
    }

    @Test
    void stepProgramsAreReDerivableFromTheSameInputs() {
        OperationContext op = new OperationContext(UUID.randomUUID(), OperationKind.ENABLE, new SpotGroup(),
                "{\"t\":1}", null);
        StepProgram first = provider.executeConversion(op);
        StepProgram second = provider.executeConversion(op);
        assertThat(first.size()).isEqualTo(second.size());
        for (int i = 0; i < first.size(); i++) {
            assertThat(first.at(i).name()).isEqualTo(second.at(i).name());
        }
        // One mutating step (APPLY_TARGET_CONFIG) at seq 2.
        assertThat(((FakeSpotProvider.FakeStep) first.at(2)).mutating()).isTrue();
        assertThat(((FakeSpotProvider.FakeStep) first.at(0)).mutating()).isFalse();
    }

    @Test
    void stepContextProducesTheDeterministicUuidV5Token() {
        UUID op = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
        String t1 = StepContext.idempotencyToken(op, 3).toString();
        String t2 = StepContext.idempotencyToken(op, 3).toString();
        String other = StepContext.idempotencyToken(op, 4).toString();
        assertThat(t1).isEqualTo(t2);
        assertThat(t1).isNotEqualTo(other);
        // Version 5 (name-based SHA-1) UUID: the 13th hex digit is '5'.
        assertThat(t1.charAt(14)).isEqualTo('5');
    }

    @Test
    void reconcileIsAPureDecision() {
        SpotGroup group = new SpotGroup();
        group.setId(UUID.randomUUID());
        assertThat(provider.reconcile(group, GroupStatus.notFound()))
                .containsExactly(ReconcileAction.MARK_GONE);
        GroupStatus healthy = provider.getStatus(group, view);
        assertThat(provider.reconcile(group, healthy)).isEmpty();
    }
}
