package com.xammer.cloud.dto.autoscaling;

/**
 * Consolidation section of the automation policy bundle (Go agent
 * {@code policy.ConsolidationPolicy}, {@code xamops-autoscaler/internal/policy/types.go}).
 *
 * <p>
 * The consolidation controller runs live node consolidation (controller-owned EMPTY/DRAIN removal).
 * It is fail-closed: served with {@code enabled = false} while the {@code consolidation-actions}
 * readiness key isn't ready (design §6.7), and a write with {@code enabled = true} while the key is
 * not ready is refused. Field names and defaults mirror the Go bundle so the agent and the UI read
 * one shape; the Go {@code Normalize()} clamps the three intervals the same way this DTO defaults them.
 */
public class ConsolidationPolicyDto {

    private boolean enabled = false;
    private int consolidateAfterSeconds = 600;
    private int maxConcurrentDisruptions = 1;
    private int dwellSeconds = 600;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getConsolidateAfterSeconds() { return consolidateAfterSeconds; }
    public void setConsolidateAfterSeconds(int consolidateAfterSeconds) {
        this.consolidateAfterSeconds = consolidateAfterSeconds;
    }

    public int getMaxConcurrentDisruptions() { return maxConcurrentDisruptions; }
    public void setMaxConcurrentDisruptions(int maxConcurrentDisruptions) {
        this.maxConcurrentDisruptions = maxConcurrentDisruptions;
    }

    public int getDwellSeconds() { return dwellSeconds; }
    public void setDwellSeconds(int dwellSeconds) { this.dwellSeconds = dwellSeconds; }
}
