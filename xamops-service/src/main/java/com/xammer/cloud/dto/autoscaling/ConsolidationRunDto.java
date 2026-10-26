package com.xammer.cloud.dto.autoscaling;

import com.xammer.cloud.dto.autoscaling.ConsolidationPlanDto.Actor;
import com.xammer.cloud.dto.autoscaling.ConsolidationPlanDto.MoveType;
import com.xammer.cloud.dto.autoscaling.ConsolidationPlanDto.NodeRef;

import java.util.List;

/**
 * The consolidation controller's run-history record, pushed by xamops-automation as the LIST
 * collector dataType {@link com.xammer.cloud.service.EksAutoscalingService#DT_CONSOLIDATION_RUNS}
 * (each run appends one record; it is never a single server-owned object).
 *
 * <p>
 * The ACTING counterpart of the look-only {@link ConsolidationPlanDto}: the plan says what could be
 * removed, the run says what the controller actually did this cycle (or simulated / refused /
 * deferred / skipped / aborted). Mirrors the Go {@code internal/consolidation.ConsolidationRunDto}
 * JSON exactly ({@code xamops-autoscaler/internal/consolidation/run.go}, {@code RunSchemaVersion = 1}):
 * no field is omitted there, so empty lists arrive as {@code []}. The run reuses the plan's
 * {@link Actor} and {@link NodeRef} and the {@link MoveType} enum ({@code EMPTY}/{@code DRAIN}).
 *
 * @param schemaVersion run-record schema version, 1
 * @param runId         the operationId shared by the paired autoscaling-actions / -events records
 *                      ({@code consolidation-<unixSeconds>})
 * @param generatedAt   RFC 3339 UTC time the controller built this record (the injected now)
 * @param mode          the gate mode the controller ran in: {@code simulate} | {@code live}
 * @param dryRun        true when {@code mode != live} (a simulation took no cloud or cluster action)
 * @param actor         the plan's detected node-removal actor
 * @param summary       counts of this run's node verdicts
 * @param nodes         one record per node the controller considered this cycle
 */
public record ConsolidationRunDto(int schemaVersion, String runId, String generatedAt, String mode,
                                  boolean dryRun, Actor actor, RunSummary summary,
                                  List<ConsolidationNodeRun> nodes) {

    /** Node verdict status strings (mirror the Go {@code RunStatus*} constants). */
    public static final String STATUS_REMOVED = "removed";
    public static final String STATUS_DEFERRED = "deferred";
    public static final String STATUS_SIMULATED = "simulated";
    public static final String STATUS_SKIPPED = "skipped";
    public static final String STATUS_REFUSED = "refused";
    public static final String STATUS_ABORTED = "aborted";

    /** Removal decision strings carried by {@link ConsolidationNodeRun#decision()}. */
    public static final String DECISION_XAMOPS_ASG = "xamops-asg";
    public static final String DECISION_DEFER_TO_ACTOR = "defer-to-actor";
    public static final String DECISION_REFUSE = "refuse";

    /**
     * Counts a run's node verdicts. {@code considered == nodes.size()}.
     *
     * @param considered  nodes the controller considered this cycle
     * @param removed     nodes the controller removed itself (XamopsASG terminate verified)
     * @param deferred    nodes cordoned + drained and left to the cluster's actor (defer-to-actor)
     * @param simulated   nodes a dry-run would have acted on (no cluster/cloud change)
     * @param skipped     candidates dropped by the dwell / cap / recheck pre-filters
     * @param refused     nodes refused fail-closed by the removal decision
     * @param aborted     nodes whose drain aborted (left cordoned, not terminated)
     * @param podsEvicted total pods this run evicted across its live drains
     */
    public record RunSummary(int considered, int removed, int deferred, int simulated, int skipped,
                             int refused, int aborted, int podsEvicted) {
    }

    /**
     * One node's outcome in a run.
     *
     * @param node        the node (same {@link NodeRef} shape the plan carries, incl. providerId)
     * @param moveType    the plan move type the controller acted on ({@code EMPTY} | {@code DRAIN})
     * @param status      the controller's outcome for this node (see {@code STATUS_*})
     * @param decision    the removal decision string (see {@code DECISION_*}), empty when the node
     *                    never reached the removal step (skipped before drain)
     * @param reason      the human sentence explaining {@code status}
     * @param podsEvicted how many pods the controller evicted draining this node
     */
    public record ConsolidationNodeRun(NodeRef node, MoveType moveType, String status, String decision,
                                       String reason, int podsEvicted) {
    }
}
