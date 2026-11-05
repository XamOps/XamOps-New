package com.xammer.cloud.service.autospotting.core.reconcile;

import com.xammer.cloud.domain.autospotting.ConfigMode;
import com.xammer.cloud.domain.autospotting.DriftStatus;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.service.autospotting.spi.GroupStatus;
import org.springframework.stereotype.Component;

/**
 * Pure drift classification of a group's live status against its owned fingerprint (design-core.md
 * §5.2, plan-phase-08.md task 8.2). No I/O: {@link SpotReconciler} reads {@code getStatus} and hands
 * the result here, and the classifier returns one of the DR codes and the response the sweep should
 * apply. A permission error is never drift (DR-7 note): it sets {@code last_error_code=PERMISSION},
 * alerts, and leaves the state unchanged.
 *
 * <p>The live fingerprint is compared with {@code desired_fingerprint} when
 * {@code config_mode=MANAGED} and with {@code fallback_fingerprint} when {@code config_mode=FALLBACK}
 * (§5.2): the fallback configuration differs from {@code desired_config} by design, so comparing it
 * with {@code desired_fingerprint} would flag every fallback as DR-2 and park it in PAUSED(DRIFT)
 * forever. After a FALLBACK_ENTER, two sweeps therefore classify drift as NONE (verified by
 * {@code DriftClassifierTest}). A customer edit of a managed field during FALLBACK is still DR-2.
 *
 * <p>GONE (DR-7) is decided by {@link GoneDetector}, not here: it needs two misses two sweeps apart,
 * which is state across sweeps, while this classifier is a pure per-status decision.
 */
@Component
public class DriftClassifier {

    /** The drift codes of §5.2 the classifier can return (GONE is decided by {@link GoneDetector}). */
    public enum Code {
        /** No drift: the live fingerprint matches the owned fingerprint. */
        NONE,
        /** DR-1: capacity bounds changed (outside the fingerprint). MINOR; alert when desired ≤ 1. */
        DR1_CAPACITY,
        /** DR-2: a managed field changed by someone else. MATERIAL → PAUSED(DRIFT) + alert + notify. */
        DR2_MANAGED_FIELD,
        /** DR-3: the XamOps marker (tag/label) was removed, fingerprint intact. MINOR. */
        DR3_MARKER_REMOVED,
        /** DR-4: an owned artifact is missing. MATERIAL → PAUSED(DRIFT). */
        DR4_ARTIFACT_MISSING,
        /** DR-5: the snapshot target is missing. Recorded on the group; no state change. */
        DR5_SNAPSHOT_MISSING,
        /** DR-6: a foreign in-flight change. Defer; ops go WAITING(FOREIGN_CHANGE). */
        DR6_FOREIGN_CHANGE,
        /** A permission error on getStatus: never drift; sets last_error_code=PERMISSION and alerts. */
        PERMISSION
    }

    /**
     * One classification result. {@code code} is the DR code; {@code materiality} the
     * {@link DriftStatus} to record ({@code NONE}/{@code MINOR}/{@code MATERIAL}); {@code pausesDrift}
     * true when the group must go PAUSED(DRIFT); {@code alert} true when it must raise an alert;
     * {@code defersOps} true for DR-6; {@code recordOnGroup} true for DR-5.
     */
    public record Classification(
            Code code,
            DriftStatus materiality,
            boolean pausesDrift,
            boolean alert,
            boolean defersOps,
            boolean recordOnGroup) {

        static Classification none() {
            return new Classification(Code.NONE, DriftStatus.NONE, false, false, false, false);
        }
    }

    /**
     * Classify the live {@code status} against the group's owned fingerprint (§5.2). {@code capacityChanged}
     * is DR-1 (capacity bounds outside the fingerprint, decided by the provider's {@code reconcile} or a
     * capacity compare); {@code desiredCapacityAtMostOne} drives the DR-1 alert (D-04); {@code markerMissing}
     * is DR-3; {@code ownedArtifactMissing} is DR-4; {@code snapshotTargetMissing} is DR-5. A permission
     * error ({@code permissionError}) short-circuits to {@link Code#PERMISSION} with no drift. DR-6 is
     * {@code status.foreignActivity()}.
     *
     * <p>Materiality precedence follows §5.2: a MATERIAL fingerprint mismatch (DR-2) or missing artifact
     * (DR-4) wins over the MINOR codes; DR-6 defers before classifying mismatch, because XamOps may have
     * caused the change.
     */
    public Classification classify(SpotGroup group, GroupStatus status, boolean permissionError,
                                   boolean capacityChanged, boolean desiredCapacityAtMostOne,
                                   boolean markerMissing, boolean ownedArtifactMissing,
                                   boolean snapshotTargetMissing) {
        if (permissionError) {
            // A permission error is never drift (§5.2): record PERMISSION and alert, no state change.
            return new Classification(Code.PERMISSION, group.getDriftStatus() == null
                    ? DriftStatus.NONE : group.getDriftStatus(), false, true, false, false);
        }
        if (status != null && status.foreignActivity()) {
            // DR-6: a foreign in-flight change. Defer; XamOps ops go WAITING(FOREIGN_CHANGE).
            return new Classification(Code.DR6_FOREIGN_CHANGE, DriftStatus.NONE, false, false, true, false);
        }
        if (ownedArtifactMissing) {
            // DR-4: an owned artifact is missing → MATERIAL, PAUSED(DRIFT).
            return new Classification(Code.DR4_ARTIFACT_MISSING, DriftStatus.MATERIAL, true, true, false, false);
        }

        String owned = ownedFingerprint(group);
        String live = status == null ? null : status.liveFingerprint();
        boolean fingerprintMismatch = owned != null && live != null && !owned.equals(live);
        if (fingerprintMismatch) {
            // DR-2: a managed field changed by someone else → MATERIAL, PAUSED(DRIFT), alert, notify.
            return new Classification(Code.DR2_MANAGED_FIELD, DriftStatus.MATERIAL, true, true, false, false);
        }

        if (snapshotTargetMissing) {
            // DR-5: the snapshot target is missing. Record on the group; no state change.
            return new Classification(Code.DR5_SNAPSHOT_MISSING, group.getDriftStatus() == null
                    ? DriftStatus.NONE : group.getDriftStatus(), false, false, false, true);
        }
        if (markerMissing) {
            // DR-3: marker removed, fingerprint intact. MINOR; re-tagging is offered, not automatic.
            return new Classification(Code.DR3_MARKER_REMOVED, DriftStatus.MINOR, false, false, false, false);
        }
        if (capacityChanged) {
            // DR-1: capacity bounds changed. MINOR; alert when desired ≤ 1 (D-04). No state change.
            return new Classification(Code.DR1_CAPACITY, DriftStatus.MINOR, false, desiredCapacityAtMostOne,
                    false, false);
        }
        return Classification.none();
    }

    /** The fingerprint the group's {@code config_mode} owns (§5.2): desired for MANAGED, fallback for FALLBACK. */
    public static String ownedFingerprint(SpotGroup group) {
        return group.getConfigMode() == ConfigMode.FALLBACK
                ? group.getFallbackFingerprint()
                : group.getDesiredFingerprint();
    }

    /** Drift is classified only for groups that have an owned fingerprint to compare (§5.2). */
    public static boolean classifiable(SpotGroup group) {
        GroupState state = group.getState();
        return state == GroupState.ACTIVE || state == GroupState.DEGRADED || state == GroupState.FALLBACK
                || state == GroupState.PAUSED;
    }
}
