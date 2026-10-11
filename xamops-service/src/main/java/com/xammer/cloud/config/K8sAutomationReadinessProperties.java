package com.xammer.cloud.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Controller readiness for CloudK8s automation (design §6.7).
 *
 * <p>
 * Each flag says whether the controller behind a control has passed safety validation. Every
 * default is {@code false}: an absent property means not ready. While a key is false the
 * server refuses the matching writes and the policy normalizer coerces the matching fields on
 * every serve; the UI reads the same values through the capabilities endpoint, so flipping a
 * key needs no UI change. Flipping one needs evidence, not just a config change (§6.7).
 *
 * <p>
 * Registered by {@link K8sAutomationWebConfig} with {@code @EnableConfigurationProperties}.
 */
@ConfigurationProperties(prefix = "xamops.k8s.automation.readiness")
public class K8sAutomationReadinessProperties {

    private boolean verticalRightsizing = false;
    private boolean hpaApply = false;
    private boolean vpaApply = false;
    private boolean evictorLive = false;
    private boolean rebalancer = false;
    private boolean nodeOptimizerActions = false;
    private boolean scaleOut = false;
    private boolean nodeTemplates = false;

    /** @return whether the key is ready; unknown keys are never ready. */
    public boolean isReady(String kebabKey) {
        return K8sReadinessKey.fromKey(kebabKey).map(this::isReady).orElse(false);
    }

    public boolean isReady(K8sReadinessKey key) {
        return switch (key) {
            case VERTICAL_RIGHTSIZING -> verticalRightsizing;
            case HPA_APPLY -> hpaApply;
            case VPA_APPLY -> vpaApply;
            case EVICTOR_LIVE -> evictorLive;
            case REBALANCER -> rebalancer;
            case NODE_OPTIMIZER_ACTIONS -> nodeOptimizerActions;
            case SCALE_OUT -> scaleOut;
            case NODE_TEMPLATES -> nodeTemplates;
        };
    }

    /** Every key in §6.7 order with its current value. */
    public Map<K8sReadinessKey, Boolean> asMap() {
        Map<K8sReadinessKey, Boolean> map = new LinkedHashMap<>();
        for (K8sReadinessKey key : K8sReadinessKey.values()) {
            map.put(key, isReady(key));
        }
        return map;
    }

    public boolean isVerticalRightsizing() { return verticalRightsizing; }
    public void setVerticalRightsizing(boolean v) { this.verticalRightsizing = v; }

    public boolean isHpaApply() { return hpaApply; }
    public void setHpaApply(boolean v) { this.hpaApply = v; }

    public boolean isVpaApply() { return vpaApply; }
    public void setVpaApply(boolean v) { this.vpaApply = v; }

    public boolean isEvictorLive() { return evictorLive; }
    public void setEvictorLive(boolean v) { this.evictorLive = v; }

    public boolean isRebalancer() { return rebalancer; }
    public void setRebalancer(boolean v) { this.rebalancer = v; }

    public boolean isNodeOptimizerActions() { return nodeOptimizerActions; }
    public void setNodeOptimizerActions(boolean v) { this.nodeOptimizerActions = v; }

    public boolean isScaleOut() { return scaleOut; }
    public void setScaleOut(boolean v) { this.scaleOut = v; }

    public boolean isNodeTemplates() { return nodeTemplates; }
    public void setNodeTemplates(boolean v) { this.nodeTemplates = v; }
}
