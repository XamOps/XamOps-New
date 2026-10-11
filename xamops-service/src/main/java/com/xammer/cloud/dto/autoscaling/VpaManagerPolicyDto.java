package com.xammer.cloud.dto.autoscaling;

/**
 * VPA manager section of the automation policy bundle (Go agent {@code VPAManagerPolicy}). The VPA
 * manager is the one controller measured accurate, and analyse-only relays existing VPA objects
 * without creating any. No write endpoint: served with {@code analyseOnly = true} and
 * {@code updateMode = "Off"} while {@code vpa-apply} isn't ready (design §6.7).
 */
public class VpaManagerPolicyDto {

    private boolean enabled = true;
    private boolean analyseOnly = true;
    /** Off | Initial | Recreate | Auto */
    private String updateMode = "Off";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isAnalyseOnly() { return analyseOnly; }
    public void setAnalyseOnly(boolean analyseOnly) { this.analyseOnly = analyseOnly; }

    public String getUpdateMode() { return updateMode; }
    public void setUpdateMode(String updateMode) { this.updateMode = updateMode; }
}
