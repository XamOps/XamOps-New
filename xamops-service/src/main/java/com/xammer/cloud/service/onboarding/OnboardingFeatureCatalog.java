package com.xammer.cloud.service.onboarding;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Feature ids → labels, a tier placeholder, availability, and the phase-2 agent env flags
 * (design §5.5). OQ-1 stays a config-driven placeholder: a tier enum with safe default names and
 * NO hard-coded prices. {@code DRY_RUN} is always {@code "true"}; setting an {@code ENABLE_*} flag
 * is only layer 1 of the four-layer gate and makes nothing act.
 */
@Service
public class OnboardingFeatureCatalog {

    public static final String COST_INSIGHTS = "COST_INSIGHTS";
    public static final String NODE_CONSOLIDATION = "NODE_CONSOLIDATION";
    public static final String WORKLOAD_AUTOSCALING = "WORKLOAD_AUTOSCALING";

    /** OQ-1 placeholder tiers — names only, no prices. */
    public enum Tier {
        FREE_READ_ONLY,
        PAID_PLAN_TBD
    }

    public record Availability(boolean available, String reason) {
    }

    public record FeatureInfo(String id, String label, Tier tier, int phase) {
    }

    private static final Map<String, FeatureInfo> CATALOG = new LinkedHashMap<>();

    static {
        CATALOG.put(COST_INSIGHTS, new FeatureInfo(COST_INSIGHTS, "Cost & insights", Tier.FREE_READ_ONLY, 1));
        CATALOG.put(NODE_CONSOLIDATION, new FeatureInfo(NODE_CONSOLIDATION, "Node consolidation", Tier.PAID_PLAN_TBD, 2));
        CATALOG.put(WORKLOAD_AUTOSCALING, new FeatureInfo(WORKLOAD_AUTOSCALING, "Workload autoscaling", Tier.PAID_PLAN_TBD, 2));
    }

    public boolean isKnown(String featureId) {
        return CATALOG.containsKey(featureId);
    }

    public FeatureInfo info(String featureId) {
        return CATALOG.get(featureId);
    }

    public List<FeatureInfo> all() {
        return List.copyOf(CATALOG.values());
    }

    /**
     * Availability (design §5.5): NODE_CONSOLIDATION needs PF-COMPUTE PASS; WORKLOAD_AUTOSCALING
     * needs a read-write account connection (its operator writes go to account-gated routes the
     * per-cluster gate does not open).
     */
    public Availability availability(String featureId, boolean accountReadWrite, boolean pfComputePass) {
        switch (featureId) {
            case NODE_CONSOLIDATION -> {
                return pfComputePass
                        ? new Availability(true, null)
                        : new Availability(false, "PF_COMPUTE");
            }
            case WORKLOAD_AUTOSCALING -> {
                return accountReadWrite
                        ? new Availability(true, null)
                        : new Availability(false, "READ_ONLY_CONNECTION");
            }
            case COST_INSIGHTS -> {
                return new Availability(true, null);
            }
            default -> {
                return new Availability(false, "UNKNOWN_FEATURE");
            }
        }
    }

    /**
     * The phase-2 agent env flags for the selected features (design §5.5). {@code nodeRemoval} is
     * the §5.3 predicate value (set by the caller), controlling {@code ENABLE_NODE_REMOVAL} alone.
     * Every other flag not listed here stays "false"; {@code DRY_RUN} is always "true".
     */
    public Map<String, String> agentEnv(List<String> features, boolean nodeRemoval, String region) {
        Map<String, String> env = new LinkedHashMap<>();
        // Safe defaults — everything off, dry-run on.
        env.put("ENABLE_EVICTOR", "false");
        env.put("ENABLE_NODE_OPTIMIZER", "false");
        env.put("ENABLE_NODE_REMOVAL", "false");
        env.put("ENABLE_HPA_MANAGER", "false");
        env.put("ENABLE_VPA_MANAGER", "false");
        env.put("ENABLE_REBALANCER", "false");
        env.put("ENABLE_RIGHTSIZER", "false");
        env.put("ENABLE_RIGHTSIZER_APPLY", "false");
        env.put("DRY_RUN", "true");
        if (region != null) {
            env.put("AWS_REGION", region);
        }

        if (features != null && features.contains(NODE_CONSOLIDATION)) {
            env.put("ENABLE_EVICTOR", "true");
            env.put("ENABLE_NODE_OPTIMIZER", "true");
            env.put("ENABLE_NODE_REMOVAL", nodeRemoval ? "true" : "false");
        }
        if (features != null && features.contains(WORKLOAD_AUTOSCALING)) {
            env.put("ENABLE_HPA_MANAGER", "true");
            // ENABLE_VPA_MANAGER stays false: readiness vpa-apply says XamOps doesn't create VPAs yet.
        }
        return env;
    }
}
