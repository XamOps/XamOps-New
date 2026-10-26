package com.xammer.cloud.service.spot;

import com.xammer.cloud.service.spot.WorkloadCapture.WorkloadEntry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the workloads on a node at notice from one snapshot (design §B4.2 capture rules 1-4,
 * §B3.3). Unknown and empty are different results: {@code null} means the snapshot can't say,
 * an empty capture means it listed the node before the notice and nothing ran there.
 */
public final class WorkloadCaptureBuilder {

    /** Entries kept per row; {@code podCount} keeps the true total. */
    public static final int MAX_ENTRIES = 50;

    private static final Comparator<WorkloadEntry> ORDER = Comparator
            .comparingInt((WorkloadEntry e) -> WorkloadKeys.isMeasurable(e.kind()) ? 0 : 1)
            .thenComparing(e -> nullToEmpty(e.namespace()))
            .thenComparing(e -> nullToEmpty(e.kind()))
            .thenComparing(e -> nullToEmpty(e.name()));

    private WorkloadCaptureBuilder() {
    }

    /**
     * @return {@code null} (unknown) when there is no snapshot, it doesn't list the node, it lists
     *         the node with no qualifying pods but was taken after {@code noticedAt}, or its
     *         Deployment list was not observed while a qualifying pod is ReplicaSet-owned;
     *         an empty capture when it lists the node with no qualifying pods at or before the
     *         notice; otherwise the normalized, ordered, capped entries.
     */
    public static WorkloadCapture capture(SnapshotView snap, String nodeName, Instant noticedAt) {
        // Rule 1: no snapshot, or the snapshot doesn't list the node.
        if (snap == null || nodeName == null || !snap.listsNode(nodeName)) {
            return null;
        }
        List<SnapshotView.PodView> qualifying = new ArrayList<>();
        for (SnapshotView.PodView pod : snap.podsOn(nodeName)) {
            if (qualifies(pod)) {
                qualifying.add(pod);
            }
        }
        if (qualifying.isEmpty()) {
            // Rule 2 (empty is evidence only before the notice) / rule 1 (after it, pods may be gone).
            boolean beforeNotice = snap.snapshotAt() != null && noticedAt != null
                    && !snap.snapshotAt().isAfter(noticedAt);
            return beforeNotice ? new WorkloadCapture(List.of(), 0, snap.snapshotAt()) : null;
        }
        // Rule 3: without the Deployment list a ReplicaSet pod can't be mapped to its Deployment.
        if (!snap.deploymentsPresentAndNonEmpty()) {
            for (SnapshotView.PodView pod : qualifying) {
                if ("ReplicaSet".equals(pod.controllerKind())) {
                    return null;
                }
            }
        }
        // Rule 4: normalized, grouped, Deployment/StatefulSet first, capped.
        Map<WorkloadKeys.Key, Integer> counts = new LinkedHashMap<>();
        for (SnapshotView.PodView pod : qualifying) {
            WorkloadKeys.Key key = WorkloadKeys.normalize(pod.namespace(), pod.controllerKind(),
                    pod.controllerName(), pod.name(), snap.deploymentKeys());
            counts.merge(key, 1, Integer::sum);
        }
        List<WorkloadEntry> entries = new ArrayList<>();
        counts.forEach((k, n) -> entries.add(new WorkloadEntry(k.namespace(), k.kind(), k.name(), n)));
        entries.sort(ORDER);
        List<WorkloadEntry> capped = entries.size() > MAX_ENTRIES ? entries.subList(0, MAX_ENTRIES) : entries;
        return new WorkloadCapture(capped, qualifying.size(), snap.snapshotAt());
    }

    /** Not Succeeded/Failed and not DaemonSet-owned (DaemonSet pods are not displaced workloads). */
    static boolean qualifies(SnapshotView.PodView pod) {
        return !"Succeeded".equals(pod.phase()) && !"Failed".equals(pod.phase())
                && !"DaemonSet".equals(pod.controllerKind());
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
