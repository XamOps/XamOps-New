package com.xammer.cloud.service.autospotting.spi;

import java.util.List;

/**
 * The plan body a provider's {@code createPlan} fills (design-core.md §7.3, FR-PLN-02, AC-PLN-02).
 * It carries exactly the FR-PLN-02 fields and no top-level ones beyond them; per-cloud docs fill the
 * fields, they don't add to this shape. Cloud-neutral: {@code currentJson}/{@code targetJson}/
 * {@code diffJson} are provider-specific JSON documents.
 *
 * <ul>
 *   <li>{@code current} (as read) and {@code target}, plus a field-level {@code diff};</li>
 *   <li>{@code snapshotToStore}, with {@code exact};</li>
 *   <li>{@code eligibility}: blockers and warnings;</li>
 *   <li>{@code rollout}: method, instances to replace, surge, duration range, headroom, quota;</li>
 *   <li>{@code estimates}: one per profile, each with monthly savings, price sources and the
 *       {@code "Estimate"} label; an unknown price shows "Not priced", not 0 (AC-PLN-09);</li>
 *   <li>{@code onDemandFloor}, {@code feeBasis} and {@code revertPath};</li>
 *   <li>{@code optionsJson}: the customer choices the plan was made with and what they could still choose
 *       (AWS: {@code allowBurstable}, whether it applies, the alternatives), or null;</li>
 *   <li>{@code providerStableId}: the group's stable cloud id (ASG ARN, MIG id) read while planning, stored on
 *       a row the plan creates; null when the plan did not read one.</li>
 * </ul>
 */
public record PlanBody(
        String currentJson,
        String targetJson,
        String diffJson,
        SnapshotPayload snapshotToStore,
        EligibilityResult eligibility,
        Rollout rollout,
        List<Estimate> estimates,
        String onDemandFloorJson,
        String feeBasisJson,
        String revertPathJson,
        String providerStableId,
        String optionsJson) {

    public PlanBody {
        estimates = estimates == null ? List.of() : List.copyOf(estimates);
    }

    /** A plan body with a provider stable id and no customer options. */
    public PlanBody(String currentJson, String targetJson, String diffJson, SnapshotPayload snapshotToStore,
                    EligibilityResult eligibility, Rollout rollout, List<Estimate> estimates,
                    String onDemandFloorJson, String feeBasisJson, String revertPathJson, String providerStableId) {
        this(currentJson, targetJson, diffJson, snapshotToStore, eligibility, rollout, estimates,
                onDemandFloorJson, feeBasisJson, revertPathJson, providerStableId, null);
    }

    /** A plan body without a provider stable id (a blocked plan, or one for a group whose row already carries it). */
    public PlanBody(String currentJson, String targetJson, String diffJson, SnapshotPayload snapshotToStore,
                    EligibilityResult eligibility, Rollout rollout, List<Estimate> estimates,
                    String onDemandFloorJson, String feeBasisJson, String revertPathJson) {
        this(currentJson, targetJson, diffJson, snapshotToStore, eligibility, rollout, estimates,
                onDemandFloorJson, feeBasisJson, revertPathJson, null, null);
    }

    /** The same body with the customer-options document ({@code {"allowBurstable":..}}, AD-09). */
    public PlanBody withOptions(String options) {
        return new PlanBody(currentJson, targetJson, diffJson, snapshotToStore, eligibility, rollout, estimates,
                onDemandFloorJson, feeBasisJson, revertPathJson, providerStableId, options);
    }

    /** The rollout plan: how the conversion is applied and its bounds (§7.3). */
    public record Rollout(
            String method,
            Integer instancesToReplace,
            int surge,
            String durationRange,
            String headroom,
            String quotaJson) {
    }

    /**
     * A savings estimate for one profile (§7.3). {@code monthlySavings} is null when the price is
     * unknown, shown as "Not priced" rather than 0 (AC-PLN-09). {@code label} is always
     * {@code "Estimate"}. {@code priceSourcesJson} records {@code [{source, asOf}]}.
     */
    public record Estimate(
            String profile,
            Double monthlySavings,
            String priceSourcesJson,
            String label) {

        public static final String LABEL = "Estimate";

        public Estimate(String profile, Double monthlySavings, String priceSourcesJson) {
            this(profile, monthlySavings, priceSourcesJson, LABEL);
        }
    }
}
