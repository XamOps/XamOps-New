package com.xammer.cloud.service.autospotting.gcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The GCP conversion profiles and the standard floor N (design-gcp.md §5.7, FR-PLN-06, PO-18).
 * The Option A floor is a fixed count: the {@code xas-standard} version gets {@code targetSize.fixed=N}
 * and the Spot version the remainder. {@code T} is the MIG's current {@code targetSize}.
 *
 * <ul>
 *   <li>Conservative {@code max(1, ceil(0.5 T))}; Balanced {@code max(1, ceil(0.25 T))}; Aggressive 1;
 *       Custom 1..T-1; Full Spot 0 (opt-in, FR-PLN-04).</li>
 *   <li>N is capped at {@code T - 1}, so a converted group keeps at least one Spot VM. {@code T <= 1}
 *       is blocked ({@code GCP_SIZE_TOO_SMALL}, D-04).</li>
 *   <li>The numbers live in this one table; PO-18 approval changes data, not logic. Conservative stays
 *       preselected until PO-18 approves (C-02).</li>
 * </ul>
 */
public final class GcpProfiles {
    private GcpProfiles() {
    }

    public enum ProfileName {
        CONSERVATIVE, BALANCED, AGGRESSIVE, CUSTOM, FULL_SPOT
    }

    /** The preselected profile until PO-18 approves otherwise (C-02). */
    public static ProfileName preselectedDefault() {
        return ProfileName.CONSERVATIVE;
    }

    /** The named, non-opt-in profiles in display order. */
    public static List<ProfileName> named() {
        return List.of(ProfileName.CONSERVATIVE, ProfileName.BALANCED, ProfileName.AGGRESSIVE);
    }

    /** The standard floor N for a profile at current size {@code t}, before the cap. Custom needs {@code customN}. */
    public static int rawFloor(ProfileName name, int t, Integer customN) {
        return switch (name) {
            case CONSERVATIVE -> Math.max(1, ceilDiv(t, 2));
            case BALANCED -> Math.max(1, ceilDiv(t, 4));
            case AGGRESSIVE -> 1;
            case FULL_SPOT -> 0;
            case CUSTOM -> customN == null ? 1 : customN;
        };
    }

    /** The floor N capped at {@code T - 1} (never below 0). Full Spot stays 0. */
    public static int floor(ProfileName name, int t, Integer customN) {
        int n = rawFloor(name, t, customN);
        if (name == ProfileName.FULL_SPOT) {
            return 0;
        }
        return Math.max(0, Math.min(n, t - 1));
    }

    /** Custom rejects N = 0 and N above T - 1 (422 at confirm, AC-PLN-08). */
    public static boolean customValid(int t, Integer customN) {
        return customN != null && customN >= 1 && customN <= t - 1;
    }

    /** Standard and Spot VM counts at group size {@code size} for a fixed floor {@code n}: below n, all standard. */
    public static Counts countsAt(int n, int size) {
        int standard = Math.max(0, Math.min(n, size));
        return new Counts(standard, Math.max(0, size - standard));
    }

    public record Counts(int standard, int spot) {
    }

    /** One profile evaluated at T, with the counts at current size and at the autoscaler's min and max. */
    public record Evaluated(ProfileName name, int floorN, boolean blocked, String blockReason, boolean preselected,
                            Counts atCurrent, Counts atMin, Counts atMax) {
    }

    /** The named profiles at current size {@code t}, with counts at min and max (null when not autoscaled). */
    public static List<Evaluated> evaluateNamed(int t, Integer autoscalerMin, Integer autoscalerMax) {
        List<Evaluated> out = new ArrayList<>();
        boolean small = t <= 1;
        for (ProfileName p : named()) {
            int n = small ? 0 : floor(p, t, null);
            out.add(evaluated(p, n, small, t, autoscalerMin, autoscalerMax,
                    !small && p == preselectedDefault()));
        }
        return out;
    }

    /** Evaluate one profile (including Custom with {@code customN}, or Full Spot). */
    public static Evaluated evaluate(ProfileName p, int t, Integer customN, Integer autoscalerMin, Integer autoscalerMax) {
        boolean small = t <= 1;
        boolean customBad = p == ProfileName.CUSTOM && !customValid(t, customN);
        int n = small || customBad ? 0 : floor(p, t, customN);
        return evaluated(p, n, small || customBad, t, autoscalerMin, autoscalerMax, false);
    }

    private static Evaluated evaluated(ProfileName p, int n, boolean blocked, int t, Integer min, Integer max,
                                       boolean preselected) {
        String reason = !blocked ? null : t <= 1 ? "GCP_SIZE_TOO_SMALL" : "CUSTOM_FLOOR_INVALID";
        return new Evaluated(p, n, blocked, reason, preselected, countsAt(n, t),
                min == null ? null : countsAt(n, min), max == null ? null : countsAt(n, max));
    }

    /** Parse an SPI profile id; null when unknown (fails closed). */
    public static ProfileName parse(String id) {
        if (id == null) {
            return null;
        }
        try {
            return ProfileName.valueOf(id.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }
}
