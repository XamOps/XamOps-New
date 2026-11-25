package com.xammer.cloud.service.autospotting.spi;

import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.domain.autospotting.SpotGroup;

import java.util.List;

/**
 * The cloud-neutral provider interface (design-core.md §9.1). One implementation per cloud (AWS in
 * Phase 12, GCP in Phase 26); a {@code FakeSpotProvider} (test scope) stands in until then. The core
 * drives providers through this boundary and nothing else: providers return outcomes and plan
 * bodies, they never set a group's state (§2.3 "Owned", §9.1).
 *
 * <p>The eight methods of §9.1:
 * <ul>
 *   <li>{@link #provider()} — which cloud.</li>
 *   <li>{@link #checkEligibility} — no side effects; blockers/warnings/deferred with stable ids,
 *       same codes and shape in every {@link EvalMode} (§9.2, AC-ELG-05).</li>
 *   <li>{@link #createPlan} — read-only ({@link ReadOnlyCloudView}); fills every {@link PlanBody}
 *       field and the live fingerprint, deterministic for the same inputs (§7.2, §9.2).</li>
 *   <li>{@link #takeSnapshot} — read-only capture; exact, or {@code exact=false} with reasons.</li>
 *   <li>{@link #executeConversion} — the {@link StepProgram} for ENABLE/ADOPT/CONFIG_APPLY/
 *       PROFILE_CHANGE/FALLBACK_*, and the PRIOR_CONFIG / FALLBACK compensation program.</li>
 *   <li>{@link #restore} — the program for REVERT and SNAPSHOT compensation.</li>
 *   <li>{@link #getStatus} — bounded reads; live config, fingerprint, health, foreign activity;
 *       distinguishes NOT_FOUND from PERMISSION (§9.2).</li>
 *   <li>{@link #reconcile} — a pure decision, no I/O; returns {@link ReconcileAction}s.</li>
 * </ul>
 *
 * <p>Planning methods take a {@link ReadOnlyCloudView}, so a mutating call cannot compile in
 * planning (AC-PLN-01; the structural part is enforced by {@code AutoSpottingArchitectureTest}).
 */
public interface SpotProvider {

    /** Which cloud this provider serves. */
    Provider provider();

    /** Evaluate eligibility (no side effects). Never throws for ineligible or a failed check (§9.2). */
    EligibilityResult checkEligibility(GroupRef ref, ReadOnlyCloudView view, Profile profile, EvalMode mode);

    /** As above, with the customer's plan options. A provider with no options ignores them. */
    default EligibilityResult checkEligibility(GroupRef ref, ReadOnlyCloudView view, Profile profile, EvalMode mode,
                                               PlanOptions options) {
        return checkEligibility(ref, view, profile, mode);
    }

    /** Create a plan body from a read-only view (§7.2). Deterministic for the same inputs. */
    PlanBody createPlan(PlanRequest req, ReadOnlyCloudView view);

    /** Capture a snapshot for restore from a read-only view. */
    SnapshotPayload takeSnapshot(GroupRef ref, ReadOnlyCloudView view);

    /** The step program for a conversion (and PRIOR_CONFIG / FALLBACK compensation). */
    StepProgram executeConversion(OperationContext op);

    /** The step program for REVERT and SNAPSHOT compensation, toward {@code snapshot}. */
    StepProgram restore(OperationContext op, SnapshotPayload snapshot);

    /** The live status of a group (bounded reads; NOT_FOUND distinct from PERMISSION). */
    GroupStatus getStatus(SpotGroup group, ReadOnlyCloudView view);

    /**
     * The live fingerprint as it will read once {@code desiredConfigJson} is the group's desired_config, or null to
     * use {@link #getStatus}. A conversion's success writes desired_config and its fingerprint together, and a
     * provider whose fingerprint depends on ownership recorded in that config (AWS: the instance maintenance
     * policy, AD-08) needs the new config here, not the old row.
     */
    default String liveFingerprintFor(SpotGroup group, String desiredConfigJson, ReadOnlyCloudView view) {
        return null;
    }

    /**
     * The {@code desired_config} a successful conversion writes from its target: the target without any plan-time
     * instruction that only the op's own steps read (AWS: {@code keepLinkedSnapshot}, D-23b-03 review #6).
     */
    default String desiredConfigFromTarget(String targetConfigJson) {
        return targetConfigJson;
    }

    /**
     * The group's {@code desired_config} after a restore program ended (D-23b-01): a REVERT that reached REVERTED or
     * FAILED, or an ENABLE compensated to its snapshot. {@code doneSteps} are the names of the op's steps that ended
     * DONE, so the provider answers from what the program actually did, not from the lifecycle edge. When the
     * restore removed XamOps' configuration, the markers of what XamOps owned go; when it did not (a drain-only
     * revert, a revert that failed before restoring), the markers stay and the config says XamOps' configuration is
     * still on the group ({@link PlanOptions#CONFIG_ON_GROUP}). Returns the input when nothing changes.
     */
    default String desiredConfigAfterRestore(String desiredConfigJson, java.util.Set<String> doneSteps) {
        return desiredConfigJson;
    }
    /** A pure decision: the actions the core should execute for this group's status (no I/O). */
    List<ReconcileAction> reconcile(SpotGroup group, GroupStatus status);
}
