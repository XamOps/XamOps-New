package com.xammer.cloud.dto.autoscaling;

import java.util.List;

/**
 * The look-only node consolidation plan pushed by xamops-automation
 * (agent dataType {@code autoscaling-consolidation-plan}, stored as one object in agent_data_cache).
 *
 * <p>
 * Mirrors the Go {@code internal/consolidation.Plan} JSON exactly (no omitempty there: every field
 * is always present and empty lists are {@code []}). The plan only describes what could be removed;
 * nothing in it has been, or will be, acted on.
 *
 * @param schemaVersion plan schema version, 1
 * @param generatedAt   RFC 3339 UTC time the agent built the plan
 * @param mode          always {@code analysis}
 * @param actor         which node-removal actor the agent found on the cluster
 * @param settings      the thresholds the plan was built with
 * @param summary       counts of each node verdict
 * @param moves         nodes that could be removed (EMPTY or DRAIN)
 * @param blocked       candidate nodes that can't be removed, with every blocker
 * @param kept          nodes that are not candidates, with the reason
 */
public record ConsolidationPlanDto(int schemaVersion, String generatedAt, String mode, Actor actor,
                                   Settings settings, Summary summary, List<Move> moves,
                                   List<BlockedNode> blocked, List<KeptNode> kept) {

    /**
     * The kind of consolidation move. v1 invariant I0: only EMPTY and DRAIN exist; REPLACE is not a
     * value, so a plan that carries one fails to deserialize.
     */
    public enum MoveType { EMPTY, DRAIN }

    /** {@code kind} is KARPENTER, CLUSTER_AUTOSCALER, MULTIPLE or NONE. Display only. */
    public record Actor(String kind, List<String> evidence, String note) {
    }

    public record Settings(int underutilizedThresholdPct, int headroomPct, int maxNodesPerPlan) {
    }

    public record Summary(int nodesEvaluated, int empty, int drain, int blocked, int kept) {
    }

    /**
     * A node as the agent saw it. {@code capacityType} is the raw label value (e.g. ON_DEMAND, spot);
     * {@code providerId} is the cloud provider id (e.g. {@code aws:///<zone>/i-...}), mirroring the Go
     * {@code NodeRef.ProviderID}. Reused by {@link ConsolidationRunDto} for its per-node records.
     */
    public record NodeRef(String name, String nodeGroup, String instanceType, String capacityType,
                          String zone, boolean fargate, String providerId, double cpuRequestedPct,
                          double memRequestedPct) {
    }

    /** One pod's simulated placement: {@code pod} is ns/name, {@code owner} Kind/name ("" when none). */
    public record PodMove(String pod, String owner, String to, long cpuMillis, long memBytes) {
    }

    /** A removable node; EMPTY moves always carry {@code pods: []}. */
    public record Move(MoveType type, NodeRef node, List<PodMove> pods) {
    }

    /** {@code pod} is "" for node-level blockers; codes may carry a ':'-suffix (match on the prefix). */
    public record Blocker(String pod, String code, String reason) {
    }

    public record BlockedNode(NodeRef node, List<Blocker> blockers) {
    }

    public record KeptNode(NodeRef node, String reason) {
    }
}
