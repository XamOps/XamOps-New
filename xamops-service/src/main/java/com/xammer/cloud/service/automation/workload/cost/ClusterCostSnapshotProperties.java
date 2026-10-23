package com.xammer.cloud.service.automation.workload.cost;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The scheduled cluster-cost-snapshot loop's configuration, under
 * {@code xamops.k8s.cost.snapshot.*}, with its master switch defaulting to
 * <strong>off</strong>.
 *
 * <p>Same style as {@link K8sCostProperties} and {@code AutoApplyProperties}: a single
 * {@code @Value}-bound constructor with a {@code :} placeholder default on every field, so an absent
 * property, an empty property and a missing profile all read as the safe value. There is no path by
 * which omitting configuration turns the snapshot loop on.
 *
 * <p>Following the {@link K8sCostProperties} precedent, there is deliberately <strong>no</strong>
 * {@code KEYS} parity test and the keys need not be declared in all five profiles: the inline
 * defaults cover every absent profile, and the master switch is declared {@code true} only in
 * {@code application-uat.properties} (next to {@code xamops.k8s.cost.enabled=true}).
 *
 * <h2>This flag is a kill switch, not the only gate</h2>
 *
 * {@link #enabled()} being true does not make anything snapshot on its own. The loop also requires
 * {@link K8sCostProperties#enabled()}; while either is false the loop does not run at all.
 */
@Component
public class ClusterCostSnapshotProperties {

    public static final String ENABLED_KEY = "xamops.k8s.cost.snapshot.enabled";
    public static final String INTERVAL_MINUTES_KEY = "xamops.k8s.cost.snapshot.interval-minutes";
    public static final String PRIOR_LOOKBACK_HOURS_KEY =
            "xamops.k8s.cost.snapshot.prior-lookback-hours";
    public static final String PRIOR_TOLERANCE_HOURS_KEY =
            "xamops.k8s.cost.snapshot.prior-tolerance-hours";

    private final boolean enabled;
    private final int intervalMinutes;
    private final int priorLookbackHours;
    private final int priorToleranceHours;

    public ClusterCostSnapshotProperties(
            @Value("${" + ENABLED_KEY + ":false}") boolean enabled,
            @Value("${" + INTERVAL_MINUTES_KEY + ":1440}") int intervalMinutes,
            @Value("${" + PRIOR_LOOKBACK_HOURS_KEY + ":24}") int priorLookbackHours,
            @Value("${" + PRIOR_TOLERANCE_HOURS_KEY + ":12}") int priorToleranceHours) {
        this.enabled = enabled;
        this.intervalMinutes = intervalMinutes;
        this.priorLookbackHours = priorLookbackHours;
        this.priorToleranceHours = priorToleranceHours;
    }

    /** The global kill switch. While false the snapshot loop does not run at all. */
    public boolean enabled() {
        return enabled;
    }

    /** How often the loop captures a snapshot. Default 1440 minutes = daily. */
    public int intervalMinutes() {
        return intervalMinutes;
    }

    /** How far back the prior-period read-back looks for a baseline snapshot (hours). */
    public int priorLookbackHours() {
        return priorLookbackHours;
    }

    /** The tolerance window around the prior-period target the read-back accepts (hours). */
    public int priorToleranceHours() {
        return priorToleranceHours;
    }
}
