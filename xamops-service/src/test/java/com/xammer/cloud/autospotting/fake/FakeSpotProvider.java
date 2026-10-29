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
import com.xammer.cloud.service.autospotting.spi.ReadOnlyCloudView;
import com.xammer.cloud.service.autospotting.spi.ReconcileAction;
import com.xammer.cloud.service.autospotting.spi.SnapshotPayload;
import com.xammer.cloud.service.autospotting.spi.SpotProvider;
import com.xammer.cloud.service.autospotting.spi.Step;
import com.xammer.cloud.service.autospotting.spi.StepContext;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;
import com.xammer.cloud.service.autospotting.spi.StepProgram;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * A test-scope {@link SpotProvider} that stands in for AWS and GCP until Phases 12 and 26
 * (plan-phase-06.md task 6.2). It makes no cloud call: every method returns deterministic,
 * cloud-neutral data so the core (lifecycle, operation store, dispatcher in later phases) can be
 * exercised end to end on the fakes.
 *
 * <p>Its {@link ReadOnlyCloudView} ({@link View}) exposes only {@code get*}/{@code describe*} reads,
 * so {@code AutoSpottingArchitectureTest}'s AC-PLN-01 structural rule holds: nothing reachable from a
 * read-only view declares a mutation-shaped method. The step programs it builds are deterministic and
 * re-derivable from their inputs (§9.1).
 */
public class FakeSpotProvider implements SpotProvider {

    private final Provider provider;

    public FakeSpotProvider() {
        this(Provider.AWS);
    }

    public FakeSpotProvider(Provider provider) {
        this.provider = provider;
    }

    @Override
    public Provider provider() {
        return provider;
    }

    @Override
    public EligibilityResult checkEligibility(GroupRef ref, ReadOnlyCloudView view, Profile profile, EvalMode mode) {
        // Deterministic and never-throwing (§9.2): a well-formed ref is eligible with no blockers.
        return EligibilityResult.eligible(List.of(), List.of());
    }

    @Override
    public PlanBody createPlan(PlanRequest req, ReadOnlyCloudView view) {
        String current = "{\"fake\":\"current\"}";
        String target = "{\"fake\":\"target\",\"profile\":\"" + req.profile().id() + "\"}";
        PlanBody.Rollout rollout = new PlanBody.Rollout("SURGE_FIRST", 2, 1, "5-15m", "25%", "{\"ok\":true}");
        PlanBody.Estimate estimate = new PlanBody.Estimate(req.profile().id(), 12.34, "[{\"source\":\"fake\"}]");
        return new PlanBody(current, target, "{\"diff\":[]}",
                takeSnapshot(req.ref(), view),
                EligibilityResult.eligible(List.of(), List.of()),
                rollout, List.of(estimate), "{\"floor\":1}", "{\"contractId\":null,\"reason\":\"NO_CONTRACT\"}",
                "{\"revert\":\"snapshot\"}");
    }

    @Override
    public SnapshotPayload takeSnapshot(GroupRef ref, ReadOnlyCloudView view) {
        String payload = "{\"group\":\"" + ref.groupKey() + "\"}";
        return SnapshotPayload.exact(provider, 1, payload, "sha256:" + Integer.toHexString(payload.hashCode()));
    }

    @Override
    public StepProgram executeConversion(OperationContext op) {
        List<Step> program = new ArrayList<>();
        program.add(new FakeStep("VERIFY_PRECONDITIONS", false));
        program.add(new FakeStep("PERSIST_SNAPSHOT", false));
        program.add(new FakeStep("APPLY_TARGET_CONFIG", true));
        program.add(new FakeStep("VERIFY_ROLLOUT", false));
        return new StepProgram(program);
    }

    @Override
    public StepProgram restore(OperationContext op, SnapshotPayload snapshot) {
        List<Step> program = new ArrayList<>();
        program.add(new FakeStep("VERIFY_PRECONDITIONS", false));
        program.add(new FakeStep("RESTORE_SNAPSHOT", true));
        program.add(new FakeStep("VERIFY_RESTORED", false));
        return new StepProgram(program);
    }

    @Override
    public GroupStatus getStatus(SpotGroup group, ReadOnlyCloudView view) {
        return new GroupStatus(true, "fp:" + group.getId(), "{\"healthy\":true}",
                "{\"desired\":2,\"inService\":2}", false);
    }

    @Override
    public List<ReconcileAction> reconcile(SpotGroup group, GroupStatus status) {
        if (!status.found()) {
            return List.of(ReconcileAction.MARK_GONE);
        }
        return List.of();
    }

    /** The fake provider's read-only view (reads only; no mutation-shaped method, AC-PLN-01). */
    public static final class View implements ReadOnlyCloudView {
        public String getLiveConfig() {
            return "{\"fake\":\"live\"}";
        }

        public String describeGroup() {
            return "{\"fake\":\"group\"}";
        }
    }

    /** A deterministic no-cloud step. {@code mutating} marks whether it records the first mutation. */
    public static final class FakeStep implements Step {
        private final String name;
        private final boolean mutating;

        public FakeStep(String name, boolean mutating) {
            this.name = name;
            this.mutating = mutating;
        }

        public boolean mutating() {
            return mutating;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public StepOutcome execute(StepContext ctx) {
            if (mutating) {
                ctx.leaseHandle().assertHeld(ctx.callTimeout());
            }
            return new StepOutcome.Done("{\"step\":\"" + name + "\"}", List.of("fake-req-" + ctx.seq()));
        }

        @Override
        public StepOutcome probe(StepContext ctx) {
            return new StepOutcome.Done("{\"probe\":\"" + name + "\"}", List.of());
        }

        @Override
        public String describeIntent(StepContext ctx) {
            return "fake: would run " + name;
        }
    }
}
