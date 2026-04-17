package com.xammer.cloud.dto.autoscaling;

/**
 * Policy configuration for the XamOps Node Optimizer component.
 *
 * Controls how the node optimizer identifies under-utilized nodes for
 * consolidation, replacement, or spot conversion.
 *
 * Serialized to/from agent_data_cache (dataType = "autoscaling-policy")
 * as part of the full AutoscalerPolicyBundle JSON.
 */
public class NodeOptimizerPolicyDto {

    private boolean enabled = true;

    /**
     * Target CPU utilization % across managed nodes.
     * Nodes consistently below this threshold are consolidation candidates.
     */
    private int targetCpuUtilizationPercent = 60;

    /**
     * Target memory utilization % across managed nodes.
     * Used alongside CPU to identify truly idle nodes.
     */
    private int targetMemoryUtilizationPercent = 60;

    /**
     * How long (minutes) a node must stay below the utilization threshold
     * before the optimizer acts on it. Prevents churn from transient dips.
     */
    private int idleGracePeriodMinutes = 15;

    /**
     * When true, nodes identified for removal are cordon+drained gracefully.
     * When false, workloads are rescheduled immediately (aggressive).
     */
    private boolean gracefulConsolidation = true;

    /**
     * Maximum number of nodes the optimizer will act on in a single cycle.
     * Acts as a safety brake to prevent runaway scale-in.
     */
    private int maxConcurrentReplacements = 2;

    /**
     * When true, the optimizer will attempt to replace on-demand nodes
     * with equivalent spot instances where the workload tolerates interruption.
     */
    private boolean spotConversionEnabled = false;

    /**
     * When true, all optimization decisions are logged but no changes are applied.
     * Use for validation before enabling live mode.
     */
    private boolean dryRun = false;

    // ─── Getters / Setters ────────────────────────────────────────────────────

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getTargetCpuUtilizationPercent() { return targetCpuUtilizationPercent; }
    public void setTargetCpuUtilizationPercent(int v) { this.targetCpuUtilizationPercent = v; }

    public int getTargetMemoryUtilizationPercent() { return targetMemoryUtilizationPercent; }
    public void setTargetMemoryUtilizationPercent(int v) { this.targetMemoryUtilizationPercent = v; }

    public int getIdleGracePeriodMinutes() { return idleGracePeriodMinutes; }
    public void setIdleGracePeriodMinutes(int v) { this.idleGracePeriodMinutes = v; }

    public boolean isGracefulConsolidation() { return gracefulConsolidation; }
    public void setGracefulConsolidation(boolean v) { this.gracefulConsolidation = v; }

    public int getMaxConcurrentReplacements() { return maxConcurrentReplacements; }
    public void setMaxConcurrentReplacements(int v) { this.maxConcurrentReplacements = v; }

    public boolean isSpotConversionEnabled() { return spotConversionEnabled; }
    public void setSpotConversionEnabled(boolean v) { this.spotConversionEnabled = v; }

    public boolean isDryRun() { return dryRun; }
    public void setDryRun(boolean v) { this.dryRun = v; }
}
