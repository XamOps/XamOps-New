package com.xammer.cloud.service.automation.proposal;

import com.xammer.cloud.config.K8sReadinessKey;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The action types a proposal can carry (design 1.1). {@code HPA} and {@code VPA} are reserved and
 * never created in v1, so they are not members: an unknown value is refused (unknown means refuse).
 */
public enum ProposalActionType {
    WORKLOAD_RESIZE(false, "WORKLOAD", List.of("IN_PLACE")),
    NODE_DRAIN(true, "NODE", List.of("DRAIN", "CORDON_ONLY")),
    NODE_CONSOLIDATE(true, "NODE", List.of("EMPTY", "DRAIN")),
    REBALANCE(true, "NODE", List.of("DRAIN"));

    private final boolean nodeAction;
    private final String targetKind;
    private final List<String> operations;

    ProposalActionType(boolean nodeAction, String targetKind, List<String> operations) {
        this.nodeAction = nodeAction;
        this.targetKind = targetKind;
        this.operations = operations;
    }

    /** True for the types the Go agent executes (claim/outcome path); false for the Java workload path. */
    public boolean isNodeAction() {
        return nodeAction;
    }

    public String targetKind() {
        return targetKind;
    }

    public boolean validOperation(String operation) {
        return operation != null && operations.contains(operation);
    }

    /**
     * The readiness key that must be ready to approve/claim this type (design 3.6). {@code NODE_DRAIN}
     * from the evictor origin maps to {@code evictor-live}, every other drain origin to
     * {@code node-optimizer-actions}.
     */
    public K8sReadinessKey readinessKey(String originController) {
        return switch (this) {
            case WORKLOAD_RESIZE -> K8sReadinessKey.VERTICAL_RIGHTSIZING;
            case NODE_DRAIN -> "evictor".equals(originController)
                    ? K8sReadinessKey.EVICTOR_LIVE : K8sReadinessKey.NODE_OPTIMIZER_ACTIONS;
            case NODE_CONSOLIDATE -> K8sReadinessKey.CONSOLIDATION_ACTIONS;
            case REBALANCE -> K8sReadinessKey.REBALANCER;
        };
    }

    public static Optional<ProposalActionType> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(t -> t.name().equals(value)).findFirst();
    }
}
