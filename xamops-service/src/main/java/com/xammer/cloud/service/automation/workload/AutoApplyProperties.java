package com.xammer.cloud.service.automation.workload;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The auto-apply loop's configuration, with its master switch defaulting to <strong>off</strong>.
 *
 * <p>Same style as {@link WorkloadAutoscalingProperties} and
 * {@link com.xammer.cloud.service.automation.workload.usage.VerticalMetricsProperties}: {@code @Value}
 * with a {@code :} placeholder default on every field, so an absent property, an empty property and
 * a missing profile all read as the safe value. There is no path by which omitting configuration
 * turns the loop on. The keys are declared explicitly in all five {@code application*.properties}
 * files as well, with {@code auto-apply.enabled=false} everywhere but UAT, and
 * {@code AutoApplyPropertiesTest} reads those files from disk — a binding test cannot see a profile
 * that quietly enabled one of them.
 *
 * <h2>This flag is a kill switch, not the only gate</h2>
 *
 * {@link #isEnabled()} being true does not make anything apply. FEAT-002's loop still goes through
 * {@code VerticalApplyService.apply}, which re-runs every gate — readiness, executor presence, write
 * permission, the per-workload holds — and only a workload whose stored policy mode is {@code AUTO}
 * is a candidate. The switch is the single global stop: while it is false the loop does not run at
 * all, independently of all of those.
 */
@Component
public class AutoApplyProperties {

    public static final String ENABLED_KEY = "xamops.automation.vertical.auto-apply.enabled";
    public static final String INTERVAL_MINUTES_KEY =
            "xamops.automation.vertical.auto-apply.interval-minutes";
    public static final String CLUSTER_COOLDOWN_MINUTES_KEY =
            "xamops.automation.vertical.auto-apply.cluster-cooldown-minutes";
    public static final String CIRCUIT_BREAKER_THRESHOLD_KEY =
            "xamops.automation.vertical.auto-apply.circuit-breaker-threshold";

    /** Every key this class binds, in declaration order — what the profile-parity test iterates. */
    public static final List<String> KEYS = List.of(
            ENABLED_KEY, INTERVAL_MINUTES_KEY, CLUSTER_COOLDOWN_MINUTES_KEY,
            CIRCUIT_BREAKER_THRESHOLD_KEY);

    private final boolean enabled;
    private final int intervalMinutes;
    private final int clusterCooldownMinutes;
    private final int circuitBreakerThreshold;

    public AutoApplyProperties(
            @Value("${" + ENABLED_KEY + ":false}") boolean enabled,
            @Value("${" + INTERVAL_MINUTES_KEY + ":15}") int intervalMinutes,
            @Value("${" + CLUSTER_COOLDOWN_MINUTES_KEY + ":15}") int clusterCooldownMinutes,
            @Value("${" + CIRCUIT_BREAKER_THRESHOLD_KEY + ":3}") int circuitBreakerThreshold) {
        this.enabled = enabled;
        this.intervalMinutes = intervalMinutes;
        this.clusterCooldownMinutes = clusterCooldownMinutes;
        this.circuitBreakerThreshold = circuitBreakerThreshold;
    }

    /** The global kill switch. While false the auto-apply loop does not run at all. */
    public boolean isEnabled() {
        return enabled;
    }

    /** How often the loop evaluates each cluster. */
    public int getIntervalMinutes() {
        return intervalMinutes;
    }

    /** The minimum gap the loop leaves between two auto-applies on the same cluster. */
    public int getClusterCooldownMinutes() {
        return clusterCooldownMinutes;
    }

    /** How many consecutive failures trip the per-cluster circuit breaker. */
    public int getCircuitBreakerThreshold() {
        return circuitBreakerThreshold;
    }
}
