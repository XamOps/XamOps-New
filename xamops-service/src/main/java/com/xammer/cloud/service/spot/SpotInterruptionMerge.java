package com.xammer.cloud.service.spot;

import com.xammer.cloud.domain.K8sSpotInterruptionEvent;
import com.xammer.cloud.service.spot.WorkloadCapture.WorkloadEntry;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * The pure merge rules of a spot interruption row (design §B1 "Merge in Java", §B4.2). Unit-tested
 * without a database; the writer applies them under a row lock.
 */
public final class SpotInterruptionMerge {

    public static final String TRACKING = "tracking";
    public static final String RECOVERED = "recovered";
    public static final String NOT_OBSERVED = "not-observed";
    public static final String NODE_NOT_REMOVED = "node-not-removed";
    public static final String NO_WORKLOADS = "no-workloads";
    public static final String NO_SNAPSHOT = "no-snapshot";
    public static final String NOT_MEASURABLE = "not-measurable";

    /** Never changed by a merge, a fill or a derivation. */
    public static final Set<String> TERMINAL = Set.of(RECOVERED, NOT_OBSERVED, NODE_NOT_REMOVED);

    /** A known handling decision replaces {@code unknown} only within this bound of the first notice. */
    static final Duration HANDLING_UPGRADE_BOUND = Duration.ofMinutes(5);

    private SpotInterruptionMerge() {
    }

    /** A new row from its first observation; the caller derives {@code recovery_status}. */
    public static K8sSpotInterruptionEvent create(SpotObservation obs) {
        K8sSpotInterruptionEvent row = new K8sSpotInterruptionEvent();
        row.setClusterConfigId(obs.key().clusterConfigId());
        row.setNodeName(obs.key().nodeName());
        row.setNodeCreatedAt(obs.key().nodeCreatedAt());
        row.setInstanceId(cap(obs.instanceId(), 32));
        row.setInstanceType(cap(obs.instanceType(), 64));
        row.setZone(cap(obs.zone(), 32));
        row.setCapacityType(cap(obs.capacityType(), 16));
        row.setNodegroup(cap(obs.nodegroup(), 255));
        row.setNoticeKind(obs.kind());
        row.setSources(cap(obs.source(), 255));
        row.setNoticedAt(obs.noticedAt());
        if (SpotNoticeRules.KIND_INTERRUPTION.equals(obs.kind())) {
            row.setInterruptionNoticedAt(obs.noticedAt());
        } else if (SpotNoticeRules.KIND_REBALANCE.equals(obs.kind())) {
            row.setRebalanceNoticedAt(obs.noticedAt());
        }
        row.setNodeLastSeenAt(obs.nodeLastSeenAt());
        row.setNodeRemovedAt(obs.nodeRemovedAt());
        row.setCordonedBeforeRemoval(obs.cordoned());
        if (obs.handling() != null) {
            row.setHandlingMode(obs.handling().mode());
            row.setHandlingReason(obs.handling().reason());
        }
        return row;
    }

    /**
     * Merges a later observation: earliest times win, sources accumulate, the kind only upgrades
     * (interruption > rebalance > unconfirmed), null facts are filled and never overwritten, and
     * handling follows the precedence rule. Workloads are not touched here (fill-only-when-null,
     * by the writer).
     */
    public static void merge(K8sSpotInterruptionEvent existing, SpotObservation obs) {
        // Handling first: its anchor is the existing first notice, before this observation's times.
        mergeHandling(existing, obs);

        existing.setNoticedAt(min(existing.getNoticedAt(), obs.noticedAt()));
        if (SpotNoticeRules.KIND_INTERRUPTION.equals(obs.kind())) {
            existing.setInterruptionNoticedAt(min(existing.getInterruptionNoticedAt(), obs.noticedAt()));
        } else if (SpotNoticeRules.KIND_REBALANCE.equals(obs.kind())) {
            existing.setRebalanceNoticedAt(min(existing.getRebalanceNoticedAt(), obs.noticedAt()));
        }
        fill(existing.getNodeLastSeenAt(), obs.nodeLastSeenAt(), existing::setNodeLastSeenAt);
        fill(existing.getNodeRemovedAt(), obs.nodeRemovedAt(), existing::setNodeRemovedAt);
        fill(existing.getCordonedBeforeRemoval(), obs.cordoned(), existing::setCordonedBeforeRemoval);
        existing.setSources(cap(unionSources(existing.getSources(), obs.source()), 255));
        existing.setNoticeKind(SpotNoticeRules.strongerKind(existing.getNoticeKind(), obs.kind()));

        fill(existing.getInstanceId(), cap(obs.instanceId(), 32), existing::setInstanceId);
        fill(existing.getInstanceType(), cap(obs.instanceType(), 64), existing::setInstanceType);
        fill(existing.getZone(), cap(obs.zone(), 32), existing::setZone);
        fill(existing.getCapacityType(), cap(obs.capacityType(), 16), existing::setCapacityType);
        fill(existing.getNodegroup(), cap(obs.nodegroup(), 255), existing::setNodegroup);
    }

    /**
     * A known value is never overwritten; a known value replaces {@code unknown} only when it
     * arrives within 5 minutes of the first notice; a new row takes whatever the observation
     * carries; otherwise unknown stays unknown.
     */
    static void mergeHandling(K8sSpotInterruptionEvent existing, SpotObservation obs) {
        if (obs.handling() == null) {
            return;
        }
        Instant anchor = existing.getInterruptionNoticedAt() != null
                ? existing.getInterruptionNoticedAt() : existing.getNoticedAt();
        boolean existingKnown = SpotHandling.isKnown(existing.getHandlingMode());
        boolean obsKnown = SpotHandling.isKnown(obs.handling().mode());
        if (existingKnown) {
            return; // a known value is never overwritten
        }
        if (obsKnown && anchor != null && obs.noticedAt() != null
                && !obs.noticedAt().isAfter(anchor.plus(HANDLING_UPGRADE_BOUND))) {
            setHandling(existing, obs.handling()); // known replaces unknown within 5 min of the first notice
        } else if (existing.getHandlingMode() == null) {
            setHandling(existing, obs.handling()); // take whatever the observation carries
        }
        // else unknown stays unknown
    }

    private static void setHandling(K8sSpotInterruptionEvent row, SpotHandling handling) {
        row.setHandlingMode(handling.mode());
        row.setHandlingReason(handling.reason());
    }

    /**
     * Recovery status from the row's own state, on every write. Terminal statuses never change;
     * otherwise null workloads → no-snapshot, [] → no-workloads, no Deployment/StatefulSet →
     * not-measurable, else tracking.
     */
    public static String deriveRecoveryStatus(K8sSpotInterruptionEvent row) {
        String current = row.getRecoveryStatus();
        if (current != null && TERMINAL.contains(current)) {
            return current;
        }
        if (row.getAffectedWorkloads() == null) {
            return NO_SNAPSHOT;
        }
        List<WorkloadEntry> workloads = WorkloadCapture.fromJson(row.getAffectedWorkloads());
        if (workloads == null) {
            // Unreadable stored value: keep what the row says rather than invent a state.
            return current != null ? current : NO_SNAPSHOT;
        }
        if (workloads.isEmpty()) {
            return NO_WORKLOADS;
        }
        for (WorkloadEntry w : workloads) {
            if (WorkloadKeys.isMeasurable(w.kind())) {
                return TRACKING;
            }
        }
        return NOT_MEASURABLE;
    }

    /**
     * Fills {@code affected_workloads} when it is still unknown and the row is inside the recovery
     * window. The provider is invoked only then, so the snapshot behind it is parsed only when
     * needed. A null capture leaves the row unknown.
     *
     * @return true when the provider was asked
     */
    public static boolean fillWorkloadsIfUnknown(K8sSpotInterruptionEvent row,
                                                 WorkloadProvider provider,
                                                 Instant now, Duration recoveryWindow) {
        if (provider == null || row.getAffectedWorkloads() != null || row.getNoticedAt() == null
                || !row.getNoticedAt().isAfter(now.minus(recoveryWindow))) {
            return false;
        }
        WorkloadCapture capture = provider.capture(row.getNoticedAt());
        if (capture != null) {
            row.setAffectedWorkloads(WorkloadCapture.toJson(capture.entries()));
            row.setPodCount(capture.podCount());
            row.setWorkloadsSnapshotAt(capture.snapshotAt());
        }
        return true;
    }

    static String unionSources(String existing, String added) {
        TreeSet<String> set = new TreeSet<>();
        if (existing != null && !existing.isBlank()) {
            Arrays.stream(existing.split(",")).map(String::trim).filter(s -> !s.isEmpty()).forEach(set::add);
        }
        if (added != null && !added.isBlank()) {
            set.add(added.trim());
        }
        return String.join(",", set);
    }

    static Instant min(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return b.isBefore(a) ? b : a;
    }

    private static <T> void fill(T current, T candidate, Consumer<T> setter) {
        if (current == null && candidate != null) {
            setter.accept(candidate);
        }
    }

    static String cap(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) : s;
    }
}
