package com.xammer.cloud.service.automation.workload;

/** A pod's Kubernetes QoS class. */
public enum QosClass {
    /** Every container has CPU and memory requests equal to its limits. */
    GUARANTEED,
    /** Something is set, but not every request equals its limit. */
    BURSTABLE,
    /** Nothing is set at all. */
    BEST_EFFORT;

    /** The spelling Kubernetes and the UI use. */
    public String wireName() {
        return switch (this) {
            case GUARANTEED -> "Guaranteed";
            case BURSTABLE -> "Burstable";
            case BEST_EFFORT -> "BestEffort";
        };
    }
}
