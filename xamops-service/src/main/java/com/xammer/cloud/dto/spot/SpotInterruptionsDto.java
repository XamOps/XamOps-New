package com.xammer.cloud.dto.spot;

import java.util.List;

/**
 * {@code GET /api/eks/autoscaling/{clusterName}/spot-interruptions} (design §B5).
 *
 * <p>Every timestamp is an ISO-8601 UTC instant ({@code …Z}). {@code null} always means unknown
 * (not reported or not observed); an empty list or a zero is served only for a real observation.
 *
 * @param windowDays       the {@code days} window the events cover
 * @param truncated        true when more than {@code limit} rows fell in the window
 * @param retentionDays    how long rows are kept
 * @param recordingEnabled the recording kill switch
 * @param fleet            from the latest {@code k8s-resources} snapshot; null when there is none
 * @param coverage         what the producers last reported
 * @param events           newest first
 */
public record SpotInterruptionsDto(int windowDays,
                                   boolean truncated,
                                   int retentionDays,
                                   boolean recordingEnabled,
                                   Fleet fleet,
                                   Coverage coverage,
                                   List<Event> events) {

    /** @param spotSharePct {@code round(100·spot/total)}; null with 0 nodes */
    public record Fleet(int totalNodes, int spotNodes, Integer spotSharePct, String collectedAt) {
    }

    /**
     * @param agentSnapshotAt    the latest {@code k8s-resources} receive time
     * @param automationReportAt the latest {@code node-optimizer-report} receive time (fresh or not)
     * @param handlingNow        that report's handling decision, only while it is fresh
     */
    public record Coverage(String agentSnapshotAt, String automationReportAt, Handling handlingNow) {
    }

    /** @param mode live | simulate | off | not-eligible | unknown */
    public record Handling(String mode, String reason) {
    }

    /** The XamOps spot-interruption drain outcome ({@code ActionRecord.status}). */
    public record Drain(String status, String reason, Boolean dryRun, String finishedAt) {
    }

    public record Workload(String namespace, String kind, String name, int podsOnNode) {
    }

    /**
     * @param status                   tracking | recovered | not-observed | node-not-removed |
     *                                 no-workloads | no-snapshot | not-measurable
     * @param lowerSeconds             null unless {@code recovered}
     * @param upperSeconds             null unless {@code recovered}
     * @param recoveredByFirstSighting recovered in the snapshot that first saw the event, so no
     *                                 duration is claimed (bounds served as 0/0)
     * @param observedAt               the snapshot that observed the recovery
     */
    public record Recovery(String status, Long lowerSeconds, Long upperSeconds, boolean recoveredByFirstSighting,
                           String observedAt) {
    }

    public record Pending(String firstSeenAt, String lastSeenAt, Integer maxPods) {
    }

    /**
     * One interrupted Spot node lifecycle.
     *
     * @param noticeKind interruption | rebalance | unconfirmed
     * @param sources    nth-taint | nth-label-legacy | karpenter-event | node-removed
     * @param handling   null when no handling was recorded
     * @param drain      null unless a drain outcome was recorded
     * @param workloads  null = unknown, [] = observed none
     * @param pending    null when no Pending pod was ever seen
     */
    public record Event(Long id,
                        String nodeName,
                        String nodeCreatedAt,
                        String instanceId,
                        String instanceType,
                        String zone,
                        String capacityType,
                        String nodegroup,
                        String noticeKind,
                        List<String> sources,
                        String noticedAt,
                        String rebalanceNoticedAt,
                        String interruptionNoticedAt,
                        String nodeRemovedAt,
                        Boolean cordonedBeforeRemoval,
                        Handling handling,
                        Drain drain,
                        List<Workload> workloads,
                        Integer podCount,
                        Boolean workloadsTruncated,
                        String workloadsSnapshotAt,
                        String nodeLastSeenAt,
                        Recovery recovery,
                        Pending pending) {
    }
}
