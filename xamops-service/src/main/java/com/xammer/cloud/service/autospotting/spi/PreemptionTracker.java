package com.xammer.cloud.service.autospotting.spi;

import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.domain.autospotting.SpotGroup;

/**
 * A provider's hook into the reconciler sweep for events the cloud does not push (design-gcp.md §8, D-09: GCP has no
 * equivalent of AWS's EventBridge delivery, so preemptions are found by polling read-only APIs each sweep). The
 * sweep calls {@link #track} after {@code getStatus} and before {@code reconcile}; it records events (write-time
 * deduped) and returns the status with its cursors and rates, which the sweep persists in
 * {@code spot_group.health_state}. It never mutates the cloud and never throws into the sweep.
 */
public interface PreemptionTracker {
    /** The cloud this tracker serves. */
    Provider provider();

    /** Record what happened since the last sweep; return the status to persist and decide on. */
    GroupStatus track(SpotGroup group, GroupStatus status);
}
