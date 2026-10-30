package com.xammer.cloud.dto.autoscaling;

import java.util.List;

/**
 * The VPA manager's per-cycle run-history record, pushed by xamops-automation as the LIST collector
 * dataType {@link com.xammer.cloud.service.EksAutoscalingService#DT_VPA_RUNS} (each acting cycle
 * appends one record; it is never a single server-owned object).
 *
 * <p>
 * The ACTING counterpart of the look-only VPA recommendations: it records what the in-place resize
 * path actually did this cycle (or simulated / skipped / refused / reverted / failed). Mirrors the
 * Go {@code internal/controller.VpaRun} JSON exactly ({@code xamops-autoscaler/internal/controller/
 * vpa_manager.go}, {@code schemaVersion = 1}): no field is omitted there, so empty lists arrive as
 * {@code []} and the lenient service mapper ({@code FAIL_ON_UNKNOWN_PROPERTIES = false}) ignores a
 * future Go field.
 *
 * @param schemaVersion run-record schema version, 1
 * @param runId         the run id ({@code vpa-<unixNano>})
 * @param generatedAt   RFC 3339 UTC time the controller built this record
 * @param mode          the gate mode the controller ran in: {@code simulate} | {@code live}
 * @param dryRun        true under {@code mode=simulate} OR the agent's {@code DRY_RUN}
 * @param summary       counts of this run's container verdicts
 * @param workloads     one record per container the controller considered this cycle
 */
public record VpaRunDto(int schemaVersion, String runId, String generatedAt, String mode,
                        boolean dryRun, VpaRunSummary summary, List<VpaWorkloadRun> workloads) {

    /** Container verdict status strings (mirror the Go status constants). */
    public static final String STATUS_RESIZED = "resized";       // in-place, verified restart-free (live only)
    public static final String STATUS_SIMULATED = "simulated";   // Simulate / DRY_RUN, zero resize calls
    public static final String STATUS_SKIPPED = "skipped";       // per-cycle cap
    public static final String STATUS_REFUSED = "refused";       // classifier or not-in-place-eligible
    public static final String STATUS_REVERTED = "reverted";     // auto-revert after an OOM/throttle regression
    public static final String STATUS_FAILED = "failed";         // API / transport / stamp error

    /**
     * Counts a run's container verdicts. {@code considered == workloads.size()} minus any revert rows.
     *
     * @param considered containers the controller considered this cycle
     * @param resized    containers resized in place, verified restart-free (live only)
     * @param simulated  containers a dry-run would have resized (no cluster change)
     * @param skipped    containers dropped by the per-cycle cap
     * @param refused    containers refused by the classifier or the in-place feasibility check
     * @param reverted   in-place resizes auto-reverted after an OOM/throttle regression
     * @param failed     containers whose resize failed (API / transport / stamp error)
     */
    public record VpaRunSummary(int considered, int resized, int simulated, int skipped,
                                int refused, int reverted, int failed) {
    }

    /**
     * One container's outcome in a run.
     *
     * <p>
     * A {@code reverted} row uses the pod name as {@code workloadName} and leaves {@code workloadKind}
     * and {@code containerName} empty; every other row populates all six fields.
     *
     * @param namespace     the workload's namespace
     * @param workloadName  the workload name (the pod name on a {@code reverted} row)
     * @param workloadKind  the workload kind, empty on a {@code reverted} row
     * @param containerName the container name, empty on a {@code reverted} row
     * @param status        the controller's outcome for this container (see {@code STATUS_*})
     * @param reason        the human sentence explaining {@code status}
     */
    public record VpaWorkloadRun(String namespace, String workloadName, String workloadKind,
                                 String containerName, String status, String reason) {
    }
}
