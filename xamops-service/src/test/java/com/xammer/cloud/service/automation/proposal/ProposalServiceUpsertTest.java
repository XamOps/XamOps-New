package com.xammer.cloud.service.automation.proposal;

import com.xammer.cloud.domain.AutomationProposal;
import com.xammer.cloud.dto.autoscaling.AgentProposalDtos.BatchRequest;
import com.xammer.cloud.dto.autoscaling.AgentProposalDtos.BatchResponse;
import com.xammer.cloud.dto.autoscaling.AgentProposalDtos.Item;
import com.xammer.cloud.dto.autoscaling.AgentProposalDtos.Rebalance;
import com.xammer.cloud.dto.autoscaling.ProposalDtos.RejectRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Proposal creation and the five refresh rules of design 3.4 (AC-B16), validation (3.8) and the rebalance lifecycle (AC-B17). */
class ProposalServiceUpsertTest extends ProposalServiceTestBase {

    private AutomationProposal only(String dedupeKey, String status) {
        List<AutomationProposal> live = proposals.findAll().stream()
                .filter(p -> p.getClusterConfigId().equals(cluster.getId()))
                .filter(p -> p.getDedupeKey().equals(dedupeKey) && p.getStatus().equals(status)).toList();
        assertThat(live).hasSize(1);
        return live.get(0);
    }

    private static Item withEvidence(Item i, com.fasterxml.jackson.databind.JsonNode evidence) {
        return new Item(i.dedupeKey(), i.actionType(), i.operation(), i.originController(), i.target(), i.params(),
                i.verdict(), i.refusal(), i.gate(), i.planFingerprint(), i.dwellSeconds(), evidence);
    }

    @Test
    void aNewCandidateBecomesAPendingProposalWithAnEvent() {
        makeManual(cluster);
        BatchResponse r = send(cluster, drainItem("node-1", FP_A));
        assertThat(r.accepted()).isEqualTo(1);
        assertThat(r.rejected()).isEmpty();
        AutomationProposal p = only("NODE_DRAIN:DRAIN:node-optimizer:node-1", "PENDING");
        assertThat(p.getVersion()).isZero();
        assertThat(p.getSource()).isEqualTo("AGENT");
        assertThat(p.getExpiresAt()).isEqualTo(T0.plus(Duration.ofMinutes(120)));
        assertThat(p.getTargetIdentity()).isEqualTo("uid-node-1");
        assertThat(p.getParamsJson()).contains("\"providerId\":\"aws:///a/i-node-1\"").contains("\"nodeGroup\":\"ng-1\"");
        assertThat(events.findByProposalIdAndClusterConfigIdOrderByAtAscIdAsc(p.getId(), cluster.getId()))
                .extracting(e -> e.getEvent()).containsExactly("PROPOSED");
    }

    @Test
    void sameFingerprintOnlyRefreshesLastSeenAndEvidenceNeverVersion() throws Exception {
        makeManual(cluster);
        send(cluster, drainItem("node-1", FP_A));
        AutomationProposal before = only("NODE_DRAIN:DRAIN:node-optimizer:node-1", "PENDING");
        send(cluster, withEvidence(drainItem("node-1", FP_A), json.readTree("{\"fit\":{\"drainable\":true},\"n\":2}")));
        AutomationProposal after = only("NODE_DRAIN:DRAIN:node-optimizer:node-1", "PENDING");
        assertThat(after.getId()).isEqualTo(before.getId());
        assertThat(after.getVersion()).isZero();
        assertThat(after.getPlanFingerprint()).isEqualTo(FP_A);
        assertThat(after.getFirstSeenAt()).isEqualTo(before.getFirstSeenAt());
        assertThat(after.getLastSeenAt()).isAfter(before.getLastSeenAt());
        assertThat(after.getEvidenceJson()).contains("\"n\":2");
        assertThat(proposals.count()).isEqualTo(1);
    }

    @Test
    void aChangedFingerprintReplacesThePendingRowAndInheritsTheCandidacyStart() {
        makeManual(cluster);
        send(cluster, item("NODE_CONSOLIDATE", "EMPTY", "consolidation", "node-1", FP_A, "ALLOWED", 300));
        AutomationProposal old = only("NODE_CONSOLIDATE:EMPTY:consolidation:node-1", "PENDING");
        assertThat(old.getApprovableAfter()).isEqualTo(old.getFirstSeenAt().plusSeconds(300));
        clock.advance(Duration.ofMinutes(5));

        send(cluster, item("NODE_CONSOLIDATE", "EMPTY", "consolidation", "node-1", FP_B, "ALLOWED", 600));

        AutomationProposal stale = proposals.findByIdAndClusterConfigId(old.getId(), cluster.getId()).orElseThrow();
        assertThat(stale.getStatus()).isEqualTo("STALE");
        assertThat(stale.getStatusReason()).isEqualTo("PLAN_CHANGED");
        assertThat(stale.getVersion()).isEqualTo(1);
        AutomationProposal fresh = only("NODE_CONSOLIDATE:EMPTY:consolidation:node-1", "PENDING");
        assertThat(fresh.getVersion()).isZero();
        assertThat(fresh.getPlanFingerprint()).isEqualTo(FP_B);
        assertThat(fresh.getFirstSeenAt()).as("candidacy is continuous").isEqualTo(old.getFirstSeenAt());
        assertThat(fresh.getApprovableAfter()).as("backend recomputes from the inherited start and the new dwell")
                .isEqualTo(old.getFirstSeenAt().plusSeconds(600));
    }

    @Test
    void anApprovedRowIsFrozenAndOnlyLastSeenMoves() {
        makeManual(cluster);
        send(cluster, drainItem("node-1", FP_A));
        AutomationProposal p = only("NODE_DRAIN:DRAIN:node-optimizer:node-1", "PENDING");
        service.approve(ctx(cluster, user(5, "ann")), p.getId(),
                new com.xammer.cloud.dto.autoscaling.ProposalDtos.ApproveRequest(0L, FP_A, null));

        send(cluster, drainItem("node-1", FP_B));

        AutomationProposal approved = only("NODE_DRAIN:DRAIN:node-optimizer:node-1", "APPROVED");
        assertThat(approved.getPlanFingerprint()).isEqualTo(FP_A);
        assertThat(approved.getLastSeenAt()).isAfter(p.getLastSeenAt());
        assertThat(proposals.count()).isEqualTo(1);
    }

    @Test
    void aClearedRefusalBecomesANormalPendingProposal() {
        makeManual(cluster);
        send(cluster, item("NODE_DRAIN", "DRAIN", "node-optimizer", "node-1", FP_A, "REFUSED", null));
        AutomationProposal refused = only("NODE_DRAIN:DRAIN:node-optimizer:node-1", "REFUSED");
        assertThat(refused.getRefusalStage()).isEqualTo("PROPOSAL");
        assertThat(refused.getRefusalCodes()).isEqualTo("pdb-no-disruptions:ns/x");

        // re-seen while still refused: touched, not duplicated
        send(cluster, item("NODE_DRAIN", "DRAIN", "node-optimizer", "node-1", FP_A, "REFUSED", null));
        assertThat(proposals.count()).isEqualTo(1);

        send(cluster, drainItem("node-1", FP_B));
        assertThat(proposals.findByIdAndClusterConfigId(refused.getId(), cluster.getId()).orElseThrow().getStatus())
                .isEqualTo("STALE");
        only("NODE_DRAIN:DRAIN:node-optimizer:node-1", "PENDING");
    }

    @Test
    void anExecutionStageRefusalIsHistoryAndBlocksReproposalOnlyForTheCooldown() {
        makeManual(cluster);
        clusters.recordAgentFeatures(cluster.getId(), "manual-v1", clock.instant(), 1200);
        send(cluster, drainItem("node-1", FP_A));
        String id = only("NODE_DRAIN:DRAIN:node-optimizer:node-1", "PENDING").getId();
        service.approve(ctx(cluster, user(5, "ann")), id,
                new com.xammer.cloud.dto.autoscaling.ProposalDtos.ApproveRequest(0L, FP_A, null));
        service.claim(reload(cluster), new com.xammer.cloud.dto.autoscaling.AgentProposalDtos.ClaimRequest("c"));
        service.outcome(reload(cluster), id, outcome(id, "refused"));

        AutomationProposal done = proposals.findByIdAndClusterConfigId(id, cluster.getId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo("REFUSED");
        assertThat(done.getRefusalStage()).isEqualTo("EXECUTION");

        BatchResponse suppressed = send(cluster, drainItem("node-1", FP_A));
        assertThat(suppressed.suppressed()).extracting(s -> s.reason()).containsExactly("COOLDOWN");
        assertThat(proposals.count()).isEqualTo(1);

        clock.advance(Duration.ofMinutes(31));
        BatchResponse fresh = send(cluster, drainItem("node-1", FP_A));
        assertThat(fresh.accepted()).isEqualTo(1);
        only("NODE_DRAIN:DRAIN:node-optimizer:node-1", "PENDING");
    }

    @Test
    void aRejectedKeyIsSuppressedForTheCooldownAndExpiredOrStaleCarryNone() {
        makeManual(cluster);
        send(cluster, drainItem("node-1", FP_A));
        String id = only("NODE_DRAIN:DRAIN:node-optimizer:node-1", "PENDING").getId();
        service.reject(ctx(cluster, user(5, "ann")), id, new RejectRequest(0L, "no"));

        assertThat(send(cluster, drainItem("node-1", FP_A)).suppressed()).hasSize(1);

        // an EXPIRED row carries no cooldown
        send(cluster, drainItem("node-2", FP_A));
        String id2 = only("NODE_DRAIN:DRAIN:node-optimizer:node-2", "PENDING").getId();
        proposals.markExpired(id2, cluster.getId(), "PENDING", "TTL", clock.instant());
        assertThat(send(cluster, drainItem("node-2", FP_A)).accepted()).isEqualTo(1);
    }

    @Test
    void thePendingQueueIsCappedButRefusalsAreNot() {
        makeManual(cluster);
        props.setMaxPendingPerCluster(2);
        send(cluster, drainItem("n1", FP_A), drainItem("n2", FP_A));
        BatchResponse r = send(cluster, drainItem("n3", FP_A),
                item("NODE_DRAIN", "DRAIN", "node-optimizer", "n4", FP_B, "REFUSED", null));
        assertThat(r.rejected()).extracting(x -> x.code()).containsExactly("QUEUE_FULL");
        assertThat(r.accepted()).isEqualTo(1);
        assertThat(proposals.countByClusterConfigIdAndStatus(cluster.getId(), "PENDING")).isEqualTo(2);
        assertThat(proposals.countByClusterConfigIdAndStatus(cluster.getId(), "REFUSED")).isEqualTo(1);
    }

    @Test
    void validationRejectsEachBadItemWithItsCodeAndStillReturns200() {
        makeManual(cluster);
        Item good = drainItem("good-node", FP_A);
        BatchResponse r = send(cluster,
                good,
                withKey(drainItem("x", FP_A), ""),
                item("WORKLOAD_RESIZE", "IN_PLACE", "vertical-engine", "w", FP_A, "ALLOWED", null),
                item("NODE_DRAIN", "EMPTY", "node-optimizer", "o", FP_A, "ALLOWED", null),
                item("NODE_DRAIN", "DRAIN", "node-optimizer", "UPPER_CASE", FP_A, "ALLOWED", null),
                item("NODE_DRAIN", "DRAIN", "node-optimizer", "bad-fp", "sha256:xyz", "ALLOWED", null),
                item("NODE_CONSOLIDATE", "EMPTY", "consolidation", "nodwell", FP_A, "ALLOWED", null),
                item("NODE_CONSOLIDATE", "EMPTY", "consolidation", "bigdwell", FP_A, "ALLOWED", 86_401),
                item("NODE_DRAIN", "DRAIN", "evictor", "ok-evictor", FP_A, "WEIRD", null),
                item("NODE_DRAIN", "DRAIN", "rebalancer", "bad-origin", FP_A, "ALLOWED", null));
        assertThat(r.accepted()).isEqualTo(1);
        assertThat(r.rejected()).extracting(x -> x.code()).containsExactly("BAD_DEDUPE_KEY", "BAD_TYPE",
                "BAD_OPERATION", "BAD_TARGET", "BAD_FINGERPRINT", "BAD_DWELL", "BAD_DWELL", "BAD_VERDICT", "BAD_ORIGIN");
    }

    private static Item withKey(Item i, String key) {
        return new Item(key, i.actionType(), i.operation(), i.originController(), i.target(), i.params(),
                i.verdict(), i.refusal(), i.gate(), i.planFingerprint(), i.dwellSeconds(), i.evidence());
    }

    @Test
    void aRefusedItemNeedsReasonCodes() {
        makeManual(cluster);
        Item noCodes = new Item("NODE_DRAIN:DRAIN:node-optimizer:n", "NODE_DRAIN", "DRAIN", "node-optimizer",
                drainItem("n", FP_A).target(), drainItem("n", FP_A).params(), "REFUSED",
                new com.xammer.cloud.dto.autoscaling.AgentProposalDtos.Refusal(List.of(), ""), null, FP_A, null,
                drainItem("n", FP_A).evidence());
        assertThat(send(cluster, noCodes).rejected()).extracting(x -> x.code()).containsExactly("BAD_VERDICT");
    }

    @Test
    void anUnreadyControllerKeyRejectsTheItemAndNothingIsCreated() {
        makeManual(cluster);
        readiness.setNodeOptimizerActions(false);
        BatchResponse r = send(cluster, drainItem("n1", FP_A));
        assertThat(r.rejected()).extracting(x -> x.code()).containsExactly("CONTROLLER_NOT_READY");
        assertThat(proposals.count()).isZero();
    }

    @Test
    void envelopeProblemsAreWholeBatchErrors() {
        makeManual(cluster);
        assertThatThrownBy(() -> service.acceptBatch(reload(cluster),
                new BatchRequest(2, "c", clock.instant(), List.of(drainItem("n", FP_A)), null)))
                .isInstanceOfSatisfying(ProposalException.class, e -> assertThat(e.getCode()).isEqualTo("UNSUPPORTED_SCHEMA"));
        Item[] many = new Item[101];
        for (int i = 0; i < many.length; i++) {
            many[i] = drainItem("n" + i, FP_A);
        }
        assertThatThrownBy(() -> service.acceptBatch(reload(cluster), batch(many)))
                .isInstanceOfSatisfying(ProposalException.class, e -> assertThat(e.getCode()).isEqualTo("TOO_MANY_ITEMS"));
        assertThatThrownBy(() -> service.acceptBatch(reload(cluster), batch()))
                .isInstanceOfSatisfying(ProposalException.class, e -> assertThat(e.getCode()).isEqualTo("INVALID_REQUEST"));
        assertThatThrownBy(() -> service.acceptBatch(reload(cluster), new BatchRequest(1, "c", clock.instant(),
                List.of(drainItem("n", FP_A)), new Rebalance(true, 501))))
                .isInstanceOfSatisfying(ProposalException.class, e -> assertThat(e.getCode()).isEqualTo("BAD_REBALANCE"));
    }

    @Test
    void aBatchForAClusterThatIsNotManualIsRefused() {
        assertThatThrownBy(() -> service.acceptBatch(reload(cluster), batch(drainItem("n", FP_A))))
                .isInstanceOfSatisfying(ProposalException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getCode()).isEqualTo("NOT_MANUAL");
                });
        assertThat(proposals.count()).isZero();
    }

    @Test
    void theBatchRateLimitIsOnePerClusterPerTwoSeconds() {
        makeManual(cluster);
        service.acceptBatch(reload(cluster), batch(drainItem("n1", FP_A)));
        clock.advance(Duration.ofMillis(1500));
        assertThatThrownBy(() -> service.acceptBatch(reload(cluster), batch(drainItem("n2", FP_A))))
                .isInstanceOfSatisfying(ProposalException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(e.getCode()).isEqualTo("RATE_LIMITED");
                });
        clock.advance(Duration.ofMillis(600));
        assertThat(service.acceptBatch(reload(cluster), batch(drainItem("n2", FP_A))).accepted()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ rebalance lifecycle (AC-B17)

    private BatchRequest rebalanceBatch(boolean complete, int count, Item... items) {
        return new BatchRequest(1, "c", clock.instant(), List.of(items), new Rebalance(complete, count));
    }

    @Test
    void aCompleteBatchConsumesTheStoredRebalanceRequestInTheSameCall() {
        makeManual(cluster);
        service.acceptBatch(reload(cluster), rebalanceBatch(true, 1,
                item("REBALANCE", "DRAIN", "rebalancer", "n1", FP_A, "ALLOWED", null)));
        verify(autoscaling).consumeRebalanceRequest(any());
    }

    @Test
    void anIncompleteBatchOrOneThatHitQueueFullLeavesTheRequestRunning() {
        makeManual(cluster);
        service.acceptBatch(reload(cluster), rebalanceBatch(false, 1,
                item("REBALANCE", "DRAIN", "rebalancer", "n1", FP_A, "ALLOWED", null)));
        verify(autoscaling, never()).consumeRebalanceRequest(any());

        clock.advance(Duration.ofSeconds(3));
        props.setMaxPendingPerCluster(1);
        service.acceptBatch(reload(cluster), rebalanceBatch(true, 2,
                item("REBALANCE", "DRAIN", "rebalancer", "n2", FP_A, "ALLOWED", null)));
        verify(autoscaling, never()).consumeRebalanceRequest(any());
    }

    @Test
    void aCompleteEmptyBatchConsumesTheRequestWithNothingToApprove() {
        makeManual(cluster);
        service.acceptBatch(reload(cluster), new BatchRequest(1, "c", clock.instant(), List.of(),
                new Rebalance(true, 0)));
        verify(autoscaling).consumeRebalanceRequest(any());
        assertThat(proposals.count()).isZero();
    }

    @Test
    void aConsumeFailurePropagatesSoTheWholeBatchRollsBack() {
        makeManual(cluster);
        org.mockito.Mockito.doThrow(new IllegalStateException("boom")).when(autoscaling).consumeRebalanceRequest(any());
        assertThatThrownBy(() -> service.acceptBatch(reload(cluster), rebalanceBatch(true, 1,
                item("REBALANCE", "DRAIN", "rebalancer", "n1", FP_A, "ALLOWED", null))))
                .isInstanceOf(IllegalStateException.class);
    }

    // ------------------------------------------------------------------ helpers

    static com.xammer.cloud.dto.autoscaling.AgentProposalDtos.OutcomeRequest outcome(String id, String status) {
        return new com.xammer.cloud.dto.autoscaling.AgentProposalDtos.OutcomeRequest(1, id, status, false, false,
                "EXECUTION", "why", "proposal-" + id, null, null, null);
    }
}
