package com.xammer.cloud.service.automation.workload;

import java.time.Instant;

/**
 * The resolved policy for one workload: either its stored row's values, or
 * {@link WorkloadPolicyDefaults}. {@link #source()} says which, and the API reports it, so a number
 * is never ambiguous about whether somebody chose it.
 *
 * <p>Policy is <strong>per workload</strong>, which is what the UI's per-workload controls expect.
 * Container-level overrides are deferred: recommendations are per container, the policy is per
 * workload.
 *
 * <p>The hysteresis bands are policy fields rather than constants precisely because the specific
 * numbers are a judgement calibrated on one cluster shape (t3.medium, 1930m / 3294Mi allocatable).
 * The asymmetry and the relative-AND-absolute rule are the structural claims; the numbers are
 * tunable per tenant without a code change.
 */
public record WorkloadPolicy(WorkloadMode mode,
                             int headroomPct,
                             int percentile,
                             int windowHours,
                             int cpuFloorMillis,
                             long memFloorBytes,
                             HysteresisBand cpuIncrease,
                             HysteresisBand cpuDecrease,
                             HysteresisBand memIncrease,
                             HysteresisBand memDecrease,
                             ApplyMethod applyMethod,
                             int cooldownMinutes,
                             double maxLimitIncreaseFactor,
                             boolean protectSingleReplica,
                             double nodeCapacityFraction,
                             Instant lastAppliedAt,
                             Source source) {

    /** Where the values came from. */
    public enum Source {
        /** A {@code vertical_workload_policy} row exists for this workload. */
        WORKLOAD_ROW,
        /** No row; {@link WorkloadPolicyDefaults} applies. */
        DEFAULTS
    }

    public WorkloadPolicy {
        if (mode == null) {
            throw new IllegalArgumentException("mode is mandatory");
        }
        if (headroomPct < 0 || headroomPct > 100) {
            throw new IllegalArgumentException("headroomPct out of range [0,100]: " + headroomPct);
        }
        if (percentile < 1 || percentile > 100) {
            throw new IllegalArgumentException("percentile out of range: " + percentile);
        }
        if (windowHours < 1) {
            throw new IllegalArgumentException("windowHours must be positive: " + windowHours);
        }
        if (cpuFloorMillis <= 0) {
            throw new IllegalArgumentException("cpuFloorMillis must be positive: " + cpuFloorMillis);
        }
        if (memFloorBytes <= 0) {
            throw new IllegalArgumentException("memFloorBytes must be positive: " + memFloorBytes);
        }
        if (cpuIncrease == null || cpuDecrease == null || memIncrease == null || memDecrease == null) {
            throw new IllegalArgumentException("all four hysteresis bands are mandatory");
        }
        if (applyMethod == null) {
            throw new IllegalArgumentException("applyMethod is mandatory");
        }
        if (cooldownMinutes < 0) {
            throw new IllegalArgumentException("cooldownMinutes cannot be negative");
        }
        if (maxLimitIncreaseFactor < 1.0) {
            throw new IllegalArgumentException(
                    "maxLimitIncreaseFactor below 1.0 would lower limits: " + maxLimitIncreaseFactor);
        }
        if (nodeCapacityFraction <= 0 || nodeCapacityFraction > 1) {
            throw new IllegalArgumentException("nodeCapacityFraction out of range (0,1]: " + nodeCapacityFraction);
        }
        if (source == null) {
            throw new IllegalArgumentException("source is mandatory: a number must say whether somebody chose it");
        }
    }

    public WorkloadPolicy withMode(WorkloadMode newMode) {
        return new WorkloadPolicy(newMode, headroomPct, percentile, windowHours, cpuFloorMillis, memFloorBytes,
                cpuIncrease, cpuDecrease, memIncrease, memDecrease, applyMethod, cooldownMinutes,
                maxLimitIncreaseFactor, protectSingleReplica, nodeCapacityFraction, lastAppliedAt, source);
    }

    public WorkloadPolicy withLastAppliedAt(Instant instant) {
        return new WorkloadPolicy(mode, headroomPct, percentile, windowHours, cpuFloorMillis, memFloorBytes,
                cpuIncrease, cpuDecrease, memIncrease, memDecrease, applyMethod, cooldownMinutes,
                maxLimitIncreaseFactor, protectSingleReplica, nodeCapacityFraction, instant, source);
    }

    public WorkloadPolicy withProtectSingleReplica(boolean protect) {
        return new WorkloadPolicy(mode, headroomPct, percentile, windowHours, cpuFloorMillis, memFloorBytes,
                cpuIncrease, cpuDecrease, memIncrease, memDecrease, applyMethod, cooldownMinutes,
                maxLimitIncreaseFactor, protect, nodeCapacityFraction, lastAppliedAt, source);
    }

    /** The policy window, as a string in the API's vocabulary ({@code 24h}). */
    public String windowLabel() {
        return windowHours + "h";
    }
}
