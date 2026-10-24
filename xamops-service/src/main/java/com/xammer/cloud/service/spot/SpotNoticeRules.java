package com.xammer.cloud.service.spot;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * The backend copy of the autoscaler's notice predicate (design §B2, §B4.2 step 2) and the
 * removal-inference exclusion set (§B4.3 step 2). The keys are pinned against the Go constants in
 * {@code xamops-autoscaler/internal/controller/node_optimizer.go} by {@code SpotNoticeRulesTest};
 * the NTH taint keys are NTH {@code pkg/node/node.go}.
 */
public final class SpotNoticeRules {

    public static final String NTH_SPOT_ITN_TAINT = "aws-node-termination-handler/spot-itn";
    public static final String NTH_REBALANCE_TAINT = "aws-node-termination-handler/rebalance-recommendation";
    public static final String NTH_ACTION_REASON_LABEL = "aws-node-termination-handler/action-reason";
    public static final String NTH_ACTION_REASON_SPOT = "SpotInterruption";

    public static final String KIND_INTERRUPTION = "interruption";
    public static final String KIND_REBALANCE = "rebalance";
    public static final String KIND_UNCONFIRMED = "unconfirmed";

    public static final String SOURCE_NTH_TAINT = "nth-taint";
    public static final String SOURCE_NTH_LABEL_LEGACY = "nth-label-legacy";
    public static final String SOURCE_KARPENTER_EVENT = "karpenter-event";
    public static final String SOURCE_NODE_REMOVED = "node-removed";

    /** Taints that mark a known, non-Spot reason for a node to disappear. */
    public static final Set<String> REMOVAL_EXCLUSION_TAINTS = Set.of(
            "ToBeDeletedByClusterAutoscaler",
            "karpenter.sh/disrupted",
            "karpenter.sh/disruption",
            "aws-node-termination-handler/asg-lifecycle-termination",
            "aws-node-termination-handler/scheduled-maintenance");

    private SpotNoticeRules() {
    }

    /** A matched notice. */
    public record Notice(String kind, String source) {
    }

    /**
     * First match wins, as in the autoscaler: spot-itn taint → interruption/nth-taint; legacy label
     * {@code action-reason=SpotInterruption} → interruption/nth-label-legacy; rebalance taint →
     * rebalance/nth-taint. Null when the node carries none of them.
     */
    public static Notice classify(Collection<String> taintKeys, Map<String, String> labels) {
        Collection<String> taints = taintKeys == null ? Set.of() : taintKeys;
        if (taints.contains(NTH_SPOT_ITN_TAINT)) {
            return new Notice(KIND_INTERRUPTION, SOURCE_NTH_TAINT);
        }
        if (labels != null && NTH_ACTION_REASON_SPOT.equals(labels.get(NTH_ACTION_REASON_LABEL))) {
            return new Notice(KIND_INTERRUPTION, SOURCE_NTH_LABEL_LEGACY);
        }
        if (taints.contains(NTH_REBALANCE_TAINT)) {
            return new Notice(KIND_REBALANCE, SOURCE_NTH_TAINT);
        }
        return null;
    }

    /** True when the node carried a taint naming a known non-Spot removal cause. */
    public static boolean hasRemovalExclusionTaint(Collection<String> taintKeys) {
        if (taintKeys == null) {
            return false;
        }
        for (String key : taintKeys) {
            if (REMOVAL_EXCLUSION_TAINTS.contains(key)) {
                return true;
            }
        }
        return false;
    }

    /** Priority interruption > rebalance > unconfirmed; the higher of the two. */
    public static String strongerKind(String a, String b) {
        return rank(a) >= rank(b) ? a : b;
    }

    private static int rank(String kind) {
        if (KIND_INTERRUPTION.equals(kind)) {
            return 3;
        }
        if (KIND_REBALANCE.equals(kind)) {
            return 2;
        }
        if (KIND_UNCONFIRMED.equals(kind)) {
            return 1;
        }
        return 0;
    }
}
