package com.xammer.cloud.service.spot;

import com.xammer.cloud.domain.K8sSpotInterruptionEvent;
import com.xammer.cloud.service.spot.SpotObservation.SpotKey;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Spot nodes that disappeared between two consecutive {@code k8s-resources} snapshots (design
 * §B4.3). Pure: the tracker supplies the row-existence and XamOps-drain lookups.
 */
public final class RemovalInference {

    /** What to do with one removed Spot node. */
    public enum Decision {
        /** A row exists for the key (any status): set removal fields where still null. */
        UPDATE_EXISTING,
        /** It carried a taint naming a known non-Spot cause. */
        SKIP_EXCLUSION_TAINT,
        /** XamOps drained it (agent_events {@code xamops-node-drained}). */
        SKIP_XAMOPS_DRAIN,
        /** No known cause: record it as {@code unconfirmed}. */
        CREATE_UNCONFIRMED
    }

    private RemovalInference() {
    }

    /**
     * Spot nodes of S0 that S1 no longer lists, or an empty list when the comparison isn't
     * trustworthy: no S0, a gap over {@code maxGap} (an agent outage must never mass-produce rows),
     * or an S1 whose {@code nodes} is missing/empty (a failed partial collection).
     */
    public static List<SnapshotView.NodeView> removedSpotNodes(SnapshotView s0, SnapshotView s1,
                                                               Instant t0, Instant t1, Duration maxGap) {
        if (s0 == null || s1 == null || t0 == null || t1 == null) {
            return List.of();
        }
        if (Duration.between(t0, t1).compareTo(maxGap) > 0 || !s1.nodesPresentAndNonEmpty()) {
            return List.of();
        }
        List<SnapshotView.NodeView> out = new ArrayList<>();
        for (SnapshotView.NodeView n : s0.nodesByName().values()) {
            if (n.isSpot() && n.createdAt() != null && !s1.listsNode(n.name())) {
                out.add(n);
            }
        }
        return out;
    }

    /**
     * Steps 1-4 of §B4.3, in order. {@code xamopsDrainedNodes} is only asked when steps 1-2 don't
     * decide, so the agent_events query runs at most once per snapshot and only when needed.
     */
    public static Decision decide(SnapshotView.NodeView n0, boolean rowExists, Supplier<Set<String>> xamopsDrainedNodes) {
        if (rowExists) {
            return Decision.UPDATE_EXISTING;
        }
        if (SpotNoticeRules.hasRemovalExclusionTaint(n0.taintKeys())) {
            return Decision.SKIP_EXCLUSION_TAINT;
        }
        if (xamopsDrainedNodes.get().contains(n0.name())) {
            return Decision.SKIP_XAMOPS_DRAIN;
        }
        return Decision.CREATE_UNCONFIRMED;
    }

    /**
     * Step 1 on an existing row: {@code node_removed_at = t1}, {@code node_last_seen_at = t0} and
     * {@code cordoned_before_removal = S0's unschedulable}, each only where still null. No other
     * field changes here; the writer fills still-unknown workloads from S0 and re-derives the status.
     */
    public static void applyRemoval(K8sSpotInterruptionEvent row, Instant t0, Instant t1, boolean unschedulableInS0) {
        if (row.getNodeRemovedAt() == null) {
            row.setNodeRemovedAt(t1);
        }
        if (row.getNodeLastSeenAt() == null) {
            row.setNodeLastSeenAt(t0);
        }
        if (row.getCordonedBeforeRemoval() == null) {
            row.setCordonedBeforeRemoval(unschedulableInS0);
        }
    }

    /**
     * Step 4: an {@code unconfirmed} observation. The real removal lies in (t0, t1], so
     * {@code node_last_seen_at = t0}, {@code noticed_at = node_removed_at = t1}; handling unknown.
     */
    public static SpotObservation unconfirmed(Long clusterConfigId, SnapshotView.NodeView n0, Instant t0, Instant t1) {
        return new SpotObservation(new SpotKey(clusterConfigId, n0.name(), n0.createdAt()),
                null, n0.instanceType(), n0.zone(), n0.capacityType(), n0.nodegroup(),
                SpotNoticeRules.KIND_UNCONFIRMED, SpotNoticeRules.SOURCE_NODE_REMOVED, t1,
                new SpotHandling(SpotHandling.UNKNOWN, "Removal inferred from the XamOps agent snapshots"),
                t0, t1, n0.unschedulable());
    }
}
