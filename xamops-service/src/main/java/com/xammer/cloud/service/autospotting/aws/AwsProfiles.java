package com.xammer.cloud.service.autospotting.aws;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The AWS conversion profiles and their mapping to {@code InstancesDistribution} (design-aws.md §3,
 * PO-18). {@code OnDemandBaseCapacity} (B) is an absolute floor of always-On-Demand capacity;
 * {@code OnDemandPercentageAboveBaseCapacity} (P) is the On-Demand share of capacity above B.
 *
 * <p>The numbers are this program's PO-18 proposal and live in <strong>one table</strong> here, so
 * PO approval changes data, not logic (C-02). Conservative stays preselected until the values are
 * approved; when the preselected profile is blocked at the group's desired, nothing is preselected
 * (PD-03). The values are the working default per the step instruction.
 *
 * <p>Expected Spot count at desired D: Auto Scaling rounds the On-Demand part above base up, in
 * favour of On-Demand, so {@code spot = (D − B) − ceil((D − B) × P / 100)} (§3.1). A profile that
 * yields 0 Spot at the current desired is {@code PROFILE_NO_SPOT}; desired ≤ 1 is
 * {@code DESIRED_TOO_SMALL} for every profile (D-04).
 */
public final class AwsProfiles {

    private AwsProfiles() {
    }

    /** The named profiles of §3.1, in display order. */
    public enum ProfileName {
        CONSERVATIVE,
        BALANCED,
        AGGRESSIVE,
        CUSTOM,
        FULL_SPOT
    }

    /** The §3.1 base (B) and percent-above-base (P) for a named profile. Custom/Full Spot are special. */
    public record ProfileParams(ProfileName name, int base, int percentAboveBase, boolean optIn) {
    }

    /** Reason a profile is blocked at a given desired (maps to the §7.2 blocker codes). */
    public enum BlockReason {
        NONE,
        DESIRED_TOO_SMALL,
        PROFILE_NO_SPOT
    }

    /**
     * One profile evaluated at a desired capacity: its B/P, the rounded On-Demand and Spot counts,
     * whether it is blocked and why, and whether it is the preselected choice.
     */
    public record EvaluatedProfile(ProfileName name, int base, int percentAboveBase, boolean optIn,
                                    int onDemandCount, int spotCount, BlockReason blockReason,
                                    boolean preselected) {
        public boolean blocked() {
            return blockReason != BlockReason.NONE;
        }
    }

    // ---- The PO-18 table (§3.1). The only place the numbers live. ----
    private static final ProfileParams CONSERVATIVE = new ProfileParams(ProfileName.CONSERVATIVE, 1, 50, false);
    private static final ProfileParams BALANCED = new ProfileParams(ProfileName.BALANCED, 1, 25, false);
    private static final ProfileParams AGGRESSIVE = new ProfileParams(ProfileName.AGGRESSIVE, 1, 0, false);

    /** The preselected profile until PO-18 approves otherwise (C-02): Conservative. */
    public static ProfileName preselectedDefault() {
        return ProfileName.CONSERVATIVE;
    }

    /** The non-opt-in named profiles (Conservative, Balanced, Aggressive) with their PO-18 values. */
    public static List<ProfileParams> namedProfiles() {
        return List.of(CONSERVATIVE, BALANCED, AGGRESSIVE);
    }

    /** The Full Spot profile (B=0, P=0), opt-in only (FR-PLN-04). */
    public static ProfileParams fullSpot() {
        return new ProfileParams(ProfileName.FULL_SPOT, 0, 0, true);
    }

    /**
     * A Custom profile with the given B and P. B is bounded to {@code [1, desired-1]} unless the Full
     * Spot opt-in is set (B=0 then). P is clamped to {@code [0,100]}.
     */
    public static ProfileParams custom(int base, int percentAboveBase) {
        return new ProfileParams(ProfileName.CUSTOM, base, clampPercent(percentAboveBase), false);
    }

    /**
     * Expected Spot count at desired D for B and P: {@code (D − B) − ceil((D − B) × P / 100)} (§3.1).
     * Never negative.
     */
    public static int spotCount(int desired, int base, int percentAboveBase) {
        int aboveBase = Math.max(0, desired - base);
        int onDemandAboveBase = ceilDiv(aboveBase * clampPercent(percentAboveBase), 100);
        return Math.max(0, aboveBase - onDemandAboveBase);
    }

    /** Expected On-Demand count at desired D for B and P: {@code B + ceil((D − B) × P / 100)}, capped at D. */
    public static int onDemandCount(int desired, int base, int percentAboveBase) {
        int spot = spotCount(desired, base, percentAboveBase);
        return Math.max(0, desired - spot);
    }

    /**
     * Evaluate the three named profiles at the current desired, marking each blocked/eligible and the
     * preselected one. Desired ≤ 1 blocks every profile with {@code DESIRED_TOO_SMALL}; a profile with
     * 0 Spot at this desired is {@code PROFILE_NO_SPOT}. The preselected default is marked only when it
     * is not blocked; otherwise nothing is preselected (PD-03).
     */
    public static List<EvaluatedProfile> evaluateNamed(int desired) {
        List<EvaluatedProfile> out = new ArrayList<>();
        boolean desiredTooSmall = desired <= 1;
        ProfileName preselect = preselectedDefault();
        // First pass: decide whether the preselected default is itself blocked.
        boolean preselectBlocked = desiredTooSmall;
        if (!preselectBlocked) {
            ProfileParams def = byName(preselect);
            preselectBlocked = spotCount(desired, def.base(), def.percentAboveBase()) == 0;
        }
        for (ProfileParams p : namedProfiles()) {
            int spot = desiredTooSmall ? 0 : spotCount(desired, p.base(), p.percentAboveBase());
            int od = desiredTooSmall ? desired : onDemandCount(desired, p.base(), p.percentAboveBase());
            BlockReason reason;
            if (desiredTooSmall) {
                reason = BlockReason.DESIRED_TOO_SMALL;
            } else if (spot == 0) {
                reason = BlockReason.PROFILE_NO_SPOT;
            } else {
                reason = BlockReason.NONE;
            }
            boolean preselected = !preselectBlocked && p.name() == preselect && reason == BlockReason.NONE;
            out.add(new EvaluatedProfile(p.name(), p.base(), p.percentAboveBase(), p.optIn(),
                    od, spot, reason, preselected));
        }
        return out;
    }

    /** The §3.1 params for a named profile (named profiles only). */
    public static ProfileParams byName(ProfileName name) {
        return switch (name) {
            case CONSERVATIVE -> CONSERVATIVE;
            case BALANCED -> BALANCED;
            case AGGRESSIVE -> AGGRESSIVE;
            case FULL_SPOT -> fullSpot();
            case CUSTOM -> throw new IllegalArgumentException("Custom has no fixed params");
        };
    }

    /** The preselected profile at this desired, or empty when the default is blocked (PD-03). */
    public static Optional<ProfileName> preselectedAt(int desired) {
        return evaluateNamed(desired).stream()
                .filter(EvaluatedProfile::preselected)
                .map(EvaluatedProfile::name)
                .findFirst();
    }

    private static int clampPercent(int p) {
        return Math.max(0, Math.min(100, p));
    }

    private static int ceilDiv(int numerator, int denominator) {
        if (denominator == 0) {
            return 0;
        }
        return (numerator + denominator - 1) / denominator;
    }
}
