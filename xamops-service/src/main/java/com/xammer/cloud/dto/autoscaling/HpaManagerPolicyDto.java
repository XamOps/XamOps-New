package com.xammer.cloud.dto.autoscaling;

/**
 * HPA manager section of the automation policy bundle (Go agent {@code HPAManagerPolicy},
 * {@code xamops-autoscaler/internal/policy/types.go}). No write endpoint: the server serves it
 * with {@code analyseOnly = true} while {@code hpa-apply} isn't ready (design §6.7).
 */
public class HpaManagerPolicyDto {

    private boolean enabled = false;
    private boolean analyseOnly = true;
    private int defaultMinReplicas = 1;
    private int defaultMaxReplicas = 10;
    private int defaultTargetCpuPct = 80;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isAnalyseOnly() { return analyseOnly; }
    public void setAnalyseOnly(boolean analyseOnly) { this.analyseOnly = analyseOnly; }

    public int getDefaultMinReplicas() { return defaultMinReplicas; }
    public void setDefaultMinReplicas(int v) { this.defaultMinReplicas = v; }

    public int getDefaultMaxReplicas() { return defaultMaxReplicas; }
    public void setDefaultMaxReplicas(int v) { this.defaultMaxReplicas = v; }

    public int getDefaultTargetCpuPct() { return defaultTargetCpuPct; }
    public void setDefaultTargetCpuPct(int v) { this.defaultTargetCpuPct = v; }
}
