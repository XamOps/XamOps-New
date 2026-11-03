package com.xammer.cloud.autospotting.fake;

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
import java.util.List;
import java.util.function.Supplier;

/**
 * A test-scope {@link SpotProvider} whose conversion and restore programs are supplied per instance,
 * so a Phase 07 IT can script exact step outcomes (Done, InProgress, Failed(kind)) to exercise the
 * runner's §4.1/§9.4 control flow. Reads are deterministic; it makes no cloud call. Not wired in
 * production (test scope only).
 */
public class ProgrammableSpotProvider implements SpotProvider {

    private final Provider provider;
    private final Supplier<StepProgram> conversion;
    private final Supplier<StepProgram> restore;

    public ProgrammableSpotProvider(Provider provider, Supplier<StepProgram> conversion,
                                    Supplier<StepProgram> restore) {
        this.provider = provider;
        this.conversion = conversion;
        this.restore = restore;
    }

    @Override
    public Provider provider() {
        return provider;
    }

    @Override
    public EligibilityResult checkEligibility(GroupRef ref, ReadOnlyCloudView view, Profile profile, EvalMode mode) {
        return EligibilityResult.eligible(List.of(), List.of());
    }

    @Override
    public PlanBody createPlan(PlanRequest req, ReadOnlyCloudView view) {
        throw new UnsupportedOperationException("not used in the runner ITs");
    }

    @Override
    public SnapshotPayload takeSnapshot(GroupRef ref, ReadOnlyCloudView view) {
        return SnapshotPayload.exact(provider, 1, "{\"snap\":\"" + ref.groupKey() + "\"}", "sha256:test");
    }

    @Override
    public StepProgram executeConversion(OperationContext op) {
        return conversion.get();
    }

    @Override
    public StepProgram restore(OperationContext op, SnapshotPayload snapshot) {
        return restore.get();
    }

    @Override
    public GroupStatus getStatus(SpotGroup group, ReadOnlyCloudView view) {
        return new GroupStatus(true, "fp:" + group.getId(), "{\"healthy\":true}", "{\"desired\":2}", false);
    }

    @Override
    public List<ReconcileAction> reconcile(SpotGroup group, GroupStatus status) {
        return List.of();
    }

    /** A step that returns a scripted sequence of outcomes across calls (for retry/WAITING tests). */
    public static final class ScriptedStep implements Step {
        private final String name;
        private final boolean mutating;
        private final List<StepOutcome> outcomes;
        private int call;

        public ScriptedStep(String name, boolean mutating, List<StepOutcome> outcomes) {
            this.name = name;
            this.mutating = mutating;
            this.outcomes = List.copyOf(outcomes);
        }

        /** A step that always succeeds. */
        public static ScriptedStep done(String name, boolean mutating) {
            return new ScriptedStep(name, mutating, List.of(new StepOutcome.Done("{\"ok\":true}", List.of())));
        }

        /** A step that returns InProgress N times then Done (a cloud rollout that finishes). */
        public static ScriptedStep inProgressThenDone(String name, boolean mutating, int waits) {
            java.util.ArrayList<StepOutcome> seq = new java.util.ArrayList<>();
            for (int i = 0; i < waits; i++) {
                seq.add(new StepOutcome.InProgress(Duration.ofMillis(10), "{\"pct\":" + (i * 10) + "}"));
            }
            seq.add(new StepOutcome.Done("{\"ok\":true}", List.of()));
            return new ScriptedStep(name, mutating, seq);
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean mutates() {
            return mutating;
        }

        @Override
        public StepOutcome execute(StepContext ctx) {
            if (mutating) {
                ctx.leaseHandle().assertHeld(ctx.callTimeout());
            }
            StepOutcome outcome = outcomes.get(Math.min(call, outcomes.size() - 1));
            call++;
            return outcome;
        }

        @Override
        public StepOutcome probe(StepContext ctx) {
            // Resuming: report the last Done if we've reached it, else inconclusive (re-execute).
            return new StepOutcome.InProgress(Duration.ofMillis(10), null);
        }

        @Override
        public String describeIntent(StepContext ctx) {
            return "scripted: " + name;
        }
    }
}
