package com.xammer.cloud.config;

import com.xammer.cloud.exception.K8sAutomationReason;

import java.util.Arrays;
import java.util.Optional;

/**
 * The eight controller-readiness keys of design §6.7, in property order, each with the reason
 * the UI and server use while it is not ready and the exact user-facing message.
 *
 * <p>
 * The messages are reviewed copy, deliberately constants in code rather than configuration.
 * The retired Go rightsizer has no key: its refusal and serve-time coercion are unconditional.
 */
public enum K8sReadinessKey {

    VERTICAL_RIGHTSIZING("vertical-rightsizing", K8sAutomationReason.CONTROLLER_NOT_READY,
            "Workload autoscaling changes are disabled until the vertical rightsizer passes live validation. "
                    + "Recommendations stay visible."),
    HPA_APPLY("hpa-apply", K8sAutomationReason.CONTROLLER_NOT_READY,
            "Automated HPA changes are disabled until the HPA manager passes safety validation."),
    VPA_APPLY("vpa-apply", K8sAutomationReason.CONTROLLER_NOT_READY,
            "XamOps doesn't create or update VPA objects yet."),
    EVICTOR_LIVE("evictor-live", K8sAutomationReason.ANALYSE_ONLY,
            "Live consolidation is disabled until pod-fit simulation is available."),
    REBALANCER("rebalancer", K8sAutomationReason.CONTROLLER_NOT_READY,
            "Rebalancing is blocked: the current rebalancer can drain the same nodes repeatedly."),
    NODE_OPTIMIZER_ACTIONS("node-optimizer-actions", K8sAutomationReason.CONTROLLER_NOT_READY,
            "Node cordon, drain and spot conversion are disabled until the node optimizer passes safety validation."),
    SCALE_OUT("scale-out", K8sAutomationReason.CONTROLLER_NOT_READY,
            "Not available: the automation agent doesn't provision nodes. Your cluster autoscaler or Karpenter does."),
    NODE_TEMPLATES("node-templates", K8sAutomationReason.CONTROLLER_NOT_READY,
            "Not available: the automation agent doesn't use node templates yet.");

    private final String key;
    private final K8sAutomationReason reason;
    private final String message;

    K8sReadinessKey(String key, K8sAutomationReason reason, String message) {
        this.key = key;
        this.reason = reason;
        this.message = message;
    }

    /** Kebab-case key, as in {@code xamops.k8s.automation.readiness.<key>} and the capabilities map. */
    public String key() {
        return key;
    }

    /** {@code ANALYSE_ONLY} for {@code evictor-live}, else {@code CONTROLLER_NOT_READY}. */
    public K8sAutomationReason reason() {
        return reason;
    }

    /** Exact §6.7 message shown while the key is not ready. */
    public String message() {
        return message;
    }

    public static Optional<K8sReadinessKey> fromKey(String kebabKey) {
        return Arrays.stream(values()).filter(k -> k.key.equals(kebabKey)).findFirst();
    }
}
