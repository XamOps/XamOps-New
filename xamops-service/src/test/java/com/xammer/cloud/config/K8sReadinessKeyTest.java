package com.xammer.cloud.config;

import com.xammer.cloud.exception.K8sAutomationReason;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-005 (message accuracy only): the REBALANCER and EVICTOR_LIVE user-facing messages are
 * updated to honest current wording — rebalancing gated pending the benefit guard + soak, the
 * ledger already prevents repeated drains; consolidation gated pending analysis-reliability + soak,
 * pod-fit simulation is built. The keys, the K8sAutomationReason values and the readiness FLAG
 * state stay unchanged, and the other six messages are untouched.
 */
class K8sReadinessKeyTest {

    @Test
    void rebalancerMessageIsHonestAndKeyAndReasonUnchanged() {
        K8sReadinessKey r = K8sReadinessKey.REBALANCER;
        assertThat(r.key()).isEqualTo("rebalancer");
        assertThat(r.reason()).isEqualTo(K8sAutomationReason.CONTROLLER_NOT_READY);
        assertThat(r.message())
                .isEqualTo("Rebalancing stays gated pending the benefit guard and a soak window; "
                        + "the ledger already prevents repeated drains of the same nodes.");
        // The stale copy must be gone.
        assertThat(r.message()).doesNotContain("drain the same nodes repeatedly");
    }

    @Test
    void evictorLiveMessageIsHonestAndKeyAndReasonUnchanged() {
        K8sReadinessKey e = K8sReadinessKey.EVICTOR_LIVE;
        assertThat(e.key()).isEqualTo("evictor-live");
        assertThat(e.reason()).isEqualTo(K8sAutomationReason.ANALYSE_ONLY);
        assertThat(e.message())
                .isEqualTo("Live consolidation stays gated pending analysis-reliability work and a soak window; "
                        + "pod-fit simulation is built.");
        // The stale copy must be gone.
        assertThat(e.message()).doesNotContain("until pod-fit simulation is available");
    }

    @Test
    void theOtherSixMessagesAreUntouched() {
        assertThat(K8sReadinessKey.VERTICAL_RIGHTSIZING.message())
                .isEqualTo("Workload autoscaling changes are disabled until the vertical rightsizer passes live validation. "
                        + "Recommendations stay visible.");
        assertThat(K8sReadinessKey.HPA_APPLY.message())
                .isEqualTo("Automated HPA changes are disabled until the HPA manager passes safety validation.");
        assertThat(K8sReadinessKey.VPA_APPLY.message())
                .isEqualTo("XamOps doesn't create or update VPA objects yet.");
        assertThat(K8sReadinessKey.NODE_OPTIMIZER_ACTIONS.message())
                .isEqualTo("Node cordon, drain and spot conversion are disabled until the node optimizer passes safety validation.");
        assertThat(K8sReadinessKey.SCALE_OUT.message())
                .isEqualTo("Not available: the automation agent doesn't provision nodes. Your cluster autoscaler or Karpenter does.");
        assertThat(K8sReadinessKey.NODE_TEMPLATES.message())
                .isEqualTo("Not available: the automation agent doesn't use node templates yet.");
    }

    @Test
    void everyKeyHasAReasonAndNonBlankMessageInPropertyOrder() {
        assertThat(K8sReadinessKey.values()).extracting(K8sReadinessKey::key).containsExactly(
                "vertical-rightsizing", "hpa-apply", "vpa-apply", "evictor-live", "rebalancer",
                "node-optimizer-actions", "scale-out", "node-templates");
        for (K8sReadinessKey key : K8sReadinessKey.values()) {
            assertThat(key.message()).as(key.key()).isNotBlank();
            assertThat(key.reason()).isEqualTo(key == K8sReadinessKey.EVICTOR_LIVE
                    ? K8sAutomationReason.ANALYSE_ONLY : K8sAutomationReason.CONTROLLER_NOT_READY);
        }
    }
}
