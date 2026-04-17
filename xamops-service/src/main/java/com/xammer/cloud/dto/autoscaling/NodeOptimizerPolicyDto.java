package com.xammer.cloud.dto.autoscaling;

/**
 * Node Optimizer policy settings.
 * Mirrors the frontend NodeOptimizerPolicy interface in eksTypes.ts.
 * Stored inside PolicyBundle in agent_data_cache (dataType="autoscaling-policy").
 */
public class NodeOptimizerPolicyDto {

    private boolean enabled = false;
    private int idleCpuThresholdPct = 20;
    private int idleMemThresholdPct = 20;
    private boolean autoCordonIdleNodes = false;
    private boolean autoDrainIdleNodes = false;
    private boolean handleSpotInterruptions = true;
    /** 0 = disabled, otherwise maintain this % of nodes as spot */
    private int spotRatioTargetPct = 0;
    private boolean karpenterEnabled = false;
    private int maxParallelDrains = 1;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getIdleCpuThresholdPct() { return idleCpuThresholdPct; }
    public void setIdleCpuThresholdPct(int idleCpuThresholdPct) { this.idleCpuThresholdPct = idleCpuThresholdPct; }

    public int getIdleMemThresholdPct() { return idleMemThresholdPct; }
    public void setIdleMemThresholdPct(int idleMemThresholdPct) { this.idleMemThresholdPct = idleMemThresholdPct; }

    public boolean isAutoCordonIdleNodes() { return autoCordonIdleNodes; }
    public void setAutoCordonIdleNodes(boolean autoCordonIdleNodes) { this.autoCordonIdleNodes = autoCordonIdleNodes; }

    public boolean isAutoDrainIdleNodes() { return autoDrainIdleNodes; }
    public void setAutoDrainIdleNodes(boolean autoDrainIdleNodes) { this.autoDrainIdleNodes = autoDrainIdleNodes; }

    public boolean isHandleSpotInterruptions() { return handleSpotInterruptions; }
    public void setHandleSpotInterruptions(boolean handleSpotInterruptions) { this.handleSpotInterruptions = handleSpotInterruptions; }

    public int getSpotRatioTargetPct() { return spotRatioTargetPct; }
    public void setSpotRatioTargetPct(int spotRatioTargetPct) { this.spotRatioTargetPct = spotRatioTargetPct; }

    public boolean isKarpenterEnabled() { return karpenterEnabled; }
    public void setKarpenterEnabled(boolean karpenterEnabled) { this.karpenterEnabled = karpenterEnabled; }

    public int getMaxParallelDrains() { return maxParallelDrains; }
    public void setMaxParallelDrains(int maxParallelDrains) { this.maxParallelDrains = maxParallelDrains; }
}
