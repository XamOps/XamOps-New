package com.xammer.cloud.service.spot;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SpotNoticeRulesTest {

    /**
     * Go parity fixture: the exact keys of {@code xamops-autoscaler/internal/controller/node_optimizer.go}
     * ({@code nthSpotITNTaint}, {@code nthRebalanceTaint}, {@code nthActionReasonLabel} and the legacy
     * selector value {@code SpotInterruption}). A change on either side must change both.
     */
    private static final List<String> GO_NOTICE_KEYS = List.of(
            "aws-node-termination-handler/spot-itn",
            "aws-node-termination-handler/rebalance-recommendation",
            "aws-node-termination-handler/action-reason",
            "SpotInterruption");

    @Test
    void theKeysMatchTheAutoscaler() {
        assertThat(List.of(SpotNoticeRules.NTH_SPOT_ITN_TAINT, SpotNoticeRules.NTH_REBALANCE_TAINT,
                SpotNoticeRules.NTH_ACTION_REASON_LABEL, SpotNoticeRules.NTH_ACTION_REASON_SPOT))
                .containsExactlyElementsOf(GO_NOTICE_KEYS);
    }

    @Test
    void spotItnTaintIsAnInterruption() {
        assertThat(SpotNoticeRules.classify(List.of("aws-node-termination-handler/spot-itn"), Map.of()))
                .isEqualTo(new SpotNoticeRules.Notice("interruption", "nth-taint"));
    }

    @Test
    void rebalanceTaintIsARebalance() {
        assertThat(SpotNoticeRules.classify(List.of("aws-node-termination-handler/rebalance-recommendation"), Map.of()))
                .isEqualTo(new SpotNoticeRules.Notice("rebalance", "nth-taint"));
    }

    @Test
    void legacyLabelIsALegacyInterruption() {
        assertThat(SpotNoticeRules.classify(List.of(),
                Map.of("aws-node-termination-handler/action-reason", "SpotInterruption")))
                .isEqualTo(new SpotNoticeRules.Notice("interruption", "nth-label-legacy"));
        assertThat(SpotNoticeRules.classify(List.of(),
                Map.of("aws-node-termination-handler/action-reason", "ScheduledEvent"))).isNull();
    }

    @Test
    void firstMatchWins() {
        assertThat(SpotNoticeRules.classify(List.of("aws-node-termination-handler/rebalance-recommendation",
                "aws-node-termination-handler/spot-itn"), Map.of()).kind()).isEqualTo("interruption");
        assertThat(SpotNoticeRules.classify(List.of("aws-node-termination-handler/rebalance-recommendation"),
                Map.of("aws-node-termination-handler/action-reason", "SpotInterruption")))
                .isEqualTo(new SpotNoticeRules.Notice("interruption", "nth-label-legacy"));
    }

    @Test
    void aPlainNodeHasNoNotice() {
        assertThat(SpotNoticeRules.classify(List.of("node.kubernetes.io/not-ready"), Map.of())).isNull();
        assertThat(SpotNoticeRules.classify(null, null)).isNull();
    }

    @Test
    void theRemovalExclusionSetHoldsEveryKnownNonSpotCause() {
        assertThat(SpotNoticeRules.REMOVAL_EXCLUSION_TAINTS).containsExactlyInAnyOrder(
                "ToBeDeletedByClusterAutoscaler",
                "karpenter.sh/disrupted",
                "karpenter.sh/disruption",
                "aws-node-termination-handler/asg-lifecycle-termination",
                "aws-node-termination-handler/scheduled-maintenance");
        for (String taint : SpotNoticeRules.REMOVAL_EXCLUSION_TAINTS) {
            assertThat(SpotNoticeRules.hasRemovalExclusionTaint(List.of("other", taint))).as(taint).isTrue();
        }
        assertThat(SpotNoticeRules.hasRemovalExclusionTaint(List.of("aws-node-termination-handler/spot-itn"))).isFalse();
        assertThat(SpotNoticeRules.hasRemovalExclusionTaint(null)).isFalse();
    }

    @Test
    void kindPriority() {
        assertThat(SpotNoticeRules.strongerKind("unconfirmed", "rebalance")).isEqualTo("rebalance");
        assertThat(SpotNoticeRules.strongerKind("rebalance", "interruption")).isEqualTo("interruption");
        assertThat(SpotNoticeRules.strongerKind("interruption", "unconfirmed")).isEqualTo("interruption");
    }
}
