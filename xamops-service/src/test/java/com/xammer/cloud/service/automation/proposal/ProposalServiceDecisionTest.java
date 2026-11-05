package com.xammer.cloud.service.automation.proposal;

import com.xammer.cloud.domain.AutomationProposal;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.dto.autoscaling.AgentProposalDtos.ApprovedAction;
import com.xammer.cloud.dto.autoscaling.AgentProposalDtos.ClaimRequest;
import com.xammer.cloud.dto.autoscaling.AgentProposalDtos.OutcomeRequest;
import com.xammer.cloud.dto.autoscaling.ProposalDtos.ApproveRequest;
import com.xammer.cloud.dto.autoscaling.ProposalDtos.ProposalListDto;
import com.xammer.cloud.dto.autoscaling.ProposalDtos.RejectRequest;
import com.xammer.cloud.exception.K8sAutomationReason;
import com.xammer.cloud.exception.K8sAutomationRefusedException;
import com.xammer.cloud.security.K8sAutomationAccessService.WriteDecision;
import com.xammer.cloud.service.automation.proposal.ProposalService.DecisionResult;
import com.xammer.cloud.service.automation.proposal.ProposalService.NewProposal;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Approve, reject, claim, outcome, mode change, sweeper and list (design 3.2, 3.4 to 3.8):
 * AC-B2, B5 to B11, B14, B18, B19.
 */
class ProposalServiceDecisionTest extends ProposalServiceTestBase {

    // ------------------------------------------------------------------ fixtures

    private String pending(String node) {
        send(cluster, drainItem(node, FP_A));
        return proposals.findAll().stream().filter(p -> p.getClusterConfigId().equals(cluster.getId()))
                .filter(p -> p.getTargetRef().equals(node) && p.getStatus().equals("PENDING"))
                .findFirst().orElseThrow().getId();
    }

    private AutomationProposal row(String id) {
        em.flush();
        em.clear();
        return proposals.findByIdAndClusterConfigId(id, cluster.getId()).orElseThrow();
    }

    private DecisionResult approve(String id) {
        return service.approve(ctx(cluster, user(5, "ann")), id, new ApproveRequest(row(id).getVersion(), FP_A, null));
    }

    private String approved(String node) {
        String id = pending(node);
        approve(id);
        return id;
    }

    private static void assertRefusal(Runnable r, HttpStatus status, String code) {
        assertThatThrownBy(r::run).isInstanceOfSatisfying(ProposalException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(status);
            assertThat(e.getCode()).isEqualTo(code);
        });
    }

    private OutcomeRequest outcome(String id, String status) {
        return ProposalServiceUpsertTest.outcome(id, status);
    }

    // ------------------------------------------------------------------ approve

    @Test
    void approveMovesPendingToApprovedExactlyOnceAndRecordsWhoAndWhen() {
        makeManual(cluster);
        String id = pending("n1");
        DecisionResult r = service.approve(ctx(cluster, user(5, "ann")), id, new ApproveRequest(0L, FP_A, "go"));
        assertThat(r.status()).isEqualTo("APPROVED");
        assertThat(r.alreadyDecided()).isFalse();
        assertThat(r.dispatchWorkload()).isFalse();
        AutomationProposal p = row(id);
        assertThat(p.getStatus()).isEqualTo("APPROVED");
        assertThat(p.getVersion()).isEqualTo(1);
        assertThat(p.getDecidedBy()).isEqualTo(5L);
        assertThat(p.getDecidedByName()).isEqualTo("ann");
        assertThat(p.getDecisionNote()).isEqualTo("go");
        assertThat(p.getApprovedUntil()).isEqualTo(clock.instant().plus(Duration.ofMinutes(15)));
        assertThat(events.findByProposalIdAndClusterConfigIdOrderByAtAscIdAsc(id, cluster.getId()))
                .extracting(e -> e.getEvent()).containsExactly("PROPOSED", "APPROVED");
    }

    @Test
    void aSecondApproveByAnyUserIsAlreadyDecidedWithNoSecondTransition() {
        makeManual(cluster);
        String id = pending("n1");
        service.approve(ctx(cluster, user(5, "ann")), id, new ApproveRequest(0L, FP_A, null));
        DecisionResult again = service.approve(ctx(cluster, user(6, "bob")), id, new ApproveRequest(0L, FP_A, null));
        assertThat(again.alreadyDecided()).isTrue();
        assertThat(again.status()).isEqualTo("APPROVED");
        assertThat(row(id).getVersion()).isEqualTo(1);
        assertThat(row(id).getDecidedBy()).isEqualTo(5L);
    }

    @Test
    void approveAfterRejectAndRejectAfterApproveAreAlreadyDecided() {
        makeManual(cluster);
        String a = pending("n1");
        service.reject(ctx(cluster, user(5, "ann")), a, new RejectRequest(0L, null));
        assertRefusal(() -> service.approve(ctx(cluster, user(5, "ann")), a, new ApproveRequest(1L, FP_A, null)),
                HttpStatus.CONFLICT, "ALREADY_DECIDED");

        String b = pending("n2");
        service.approve(ctx(cluster, user(5, "ann")), b, new ApproveRequest(0L, FP_A, null));
        assertRefusal(() -> service.reject(ctx(cluster, user(5, "ann")), b, new RejectRequest(1L, null)),
                HttpStatus.CONFLICT, "ALREADY_DECIDED");
        assertThat(row(b).getStatus()).isEqualTo("APPROVED");
    }

    @Test
    void rejectIsIdempotentForTheSameResultingStatus() {
        makeManual(cluster);
        String id = pending("n1");
        assertThat(service.reject(ctx(cluster, user(5, "ann")), id, new RejectRequest(0L, "no")).alreadyDecided()).isFalse();
        DecisionResult again = service.reject(ctx(cluster, user(6, "bob")), id, new RejectRequest(0L, null));
        assertThat(again.alreadyDecided()).isTrue();
        assertThat(again.status()).isEqualTo("REJECTED");
        assertThat(row(id).getFinishedAt()).isNotNull();
    }

    @Test
    void refusedExpiredStaleAndRejectedProposalsAreNotApprovable() {
        makeManual(cluster);
        send(cluster, item("NODE_DRAIN", "DRAIN", "node-optimizer", "r1", FP_A, "REFUSED", null));
        String refused = proposals.findAll().get(0).getId();
        assertRefusal(() -> service.approve(ctx(cluster, user(5, "ann")), refused, new ApproveRequest(0L, FP_A, null)),
                HttpStatus.CONFLICT, "REFUSED_NOT_APPROVABLE");

        String expired = pending("e1");
        proposals.markExpired(expired, cluster.getId(), "PENDING", "TTL", clock.instant());
        assertRefusal(() -> service.approve(ctx(cluster, user(5, "ann")), expired, new ApproveRequest(1L, FP_A, null)),
                HttpStatus.CONFLICT, "EXPIRED");

        String stale = pending("s1");
        proposals.markStale(stale, cluster.getId(), "PENDING", "PLAN_CHANGED", clock.instant());
        assertRefusal(() -> service.approve(ctx(cluster, user(5, "ann")), stale, new ApproveRequest(1L, FP_A, null)),
                HttpStatus.CONFLICT, "STALE");
        assertThat(row(refused).getStatus()).isEqualTo("REFUSED");
        assertThat(row(expired).getStatus()).isEqualTo("EXPIRED");
    }

    @Test
    void aWrongVersionOrFingerprintIsRejectedInsideTheSameCas() {
        makeManual(cluster);
        String id = pending("n1");
        assertRefusal(() -> service.approve(ctx(cluster, user(5, "ann")), id, new ApproveRequest(7L, FP_A, null)),
                HttpStatus.CONFLICT, "VERSION_CONFLICT");
        assertRefusal(() -> service.approve(ctx(cluster, user(5, "ann")), id, new ApproveRequest(0L, FP_B, null)),
                HttpStatus.CONFLICT, "FINGERPRINT_MISMATCH");
        assertThat(row(id).getStatus()).isEqualTo("PENDING");
        assertThat(row(id).getVersion()).isZero();
    }

    @Test
    void aPendingRowPastItsTtlExpiresWhenApprovedLate() {
        makeManual(cluster);
        String id = pending("n1");
        clock.advance(Duration.ofMinutes(121));
        assertRefusal(() -> service.approve(ctx(cluster, user(5, "ann")), id, new ApproveRequest(0L, FP_A, null)),
                HttpStatus.CONFLICT, "EXPIRED");
        assertThat(row(id).getStatus()).isEqualTo("EXPIRED");
    }

    @Test
    void aProposalOfAnotherClusterIsA404ForApproveRejectAndDetail() {
        makeManual(cluster);
        makeManual(otherCluster);
        String id = pending("n1");
        var attacker = ctx(otherCluster, user(9, "mallory"));
        assertRefusal(() -> service.approve(attacker, id, new ApproveRequest(0L, FP_A, null)), HttpStatus.NOT_FOUND, "NOT_FOUND");
        assertRefusal(() -> service.reject(attacker, id, new RejectRequest(0L, null)), HttpStatus.NOT_FOUND, "NOT_FOUND");
        assertRefusal(() -> service.detail(reload(otherCluster), id), HttpStatus.NOT_FOUND, "NOT_FOUND");
        assertThat(row(id).getStatus()).isEqualTo("PENDING");
        assertThat(service.detail(reload(cluster), id).proposal().id()).isEqualTo(id);
    }

    @Test
    void approvingANodeActionNeedsARecentAgentHeartbeat() {
        makeManual(cluster);
        String id = pending("n1");
        clock.advance(Duration.ofMinutes(11));
        assertRefusal(() -> service.approve(ctx(cluster, user(5, "ann")), id, new ApproveRequest(0L, FP_A, null)),
                HttpStatus.CONFLICT, "AGENT_NOT_REACHABLE");
        // reject is never subject to the liveness check
        assertThat(service.reject(ctx(cluster, user(5, "ann")), id, new RejectRequest(0L, null)).status())
                .isEqualTo("REJECTED");

        // fresh: an agent seen 9 minutes ago is accepted
        clusters.recordPolicyServed(cluster.getId(), "MANUAL", clock.instant());
        String id2 = pending("n2");
        clock.advance(Duration.ofMinutes(9));
        assertThat(service.approve(ctx(cluster, user(5, "ann")), id2, new ApproveRequest(0L, FP_A, null)).status())
                .isEqualTo("APPROVED");
    }

    @Test
    void anAgentThatNeverCheckedInCannotHaveNodeActionsApproved() {
        makeManual(cluster);
        String id = pending("n1");
        clusters.recordPolicyServed(cluster.getId(), null, null);
        clusters.recordAgentFeatures(cluster.getId(), null, null, null);
        assertRefusal(() -> service.approve(ctx(cluster, user(5, "ann")), id, new ApproveRequest(0L, FP_A, null)),
                HttpStatus.CONFLICT, "AGENT_NOT_REACHABLE");
    }

    @Test
    void aConsolidationCannotBeApprovedBeforeItsDwellElapsed() {
        makeManual(cluster);
        send(cluster, item("NODE_CONSOLIDATE", "EMPTY", "consolidation", "n1", FP_A, "ALLOWED", 300));
        String id = proposals.findAll().get(0).getId();
        assertRefusal(() -> service.approve(ctx(cluster, user(5, "ann")), id, new ApproveRequest(0L, FP_A, null)),
                HttpStatus.CONFLICT, "NOT_YET_APPROVABLE");
        clock.advance(Duration.ofMinutes(6));
        clusters.recordPolicyServed(cluster.getId(), "MANUAL", clock.instant());
        assertThat(service.approve(ctx(cluster, user(5, "ann")), id, new ApproveRequest(0L, FP_A, null)).status())
                .isEqualTo("APPROVED");
    }

    @Test
    void approvalNeverBypassesTheGates() {
        makeManual(cluster);
        String id = pending("n1");
        readiness.setNodeOptimizerActions(false);
        assertRefusal(() -> service.approve(ctx(cluster, user(5, "ann")), id, new ApproveRequest(0L, FP_A, null)),
                HttpStatus.CONFLICT, "K8S_CONTROLLER_NOT_READY");
        readiness.setNodeOptimizerActions(true);
        readiness.setManualApproval(false);
        assertRefusal(() -> service.approve(ctx(cluster, user(5, "ann")), id, new ApproveRequest(0L, FP_A, null)),
                HttpStatus.CONFLICT, "K8S_CONTROLLER_NOT_READY");
        readiness.setManualApproval(true);
        when(autoscaling.storedAutoscalerMode(any())).thenReturn("disabled");
        assertRefusal(() -> service.approve(ctx(cluster, user(5, "ann")), id, new ApproveRequest(0L, FP_A, null)),
                HttpStatus.CONFLICT, "K8S_MODE_DISABLED");
        when(autoscaling.storedAutoscalerMode(any())).thenReturn("full");
        clusters.setApprovalMode(cluster.getId(), "AUTONOMOUS", clock.instant(), 1L);
        assertRefusal(() -> service.approve(ctx(cluster, user(5, "ann")), id, new ApproveRequest(0L, FP_A, null)),
                HttpStatus.CONFLICT, "NOT_MANUAL");
        assertThat(row(id).getStatus()).isEqualTo("PENDING");
    }

    @Test
    void theEvictorOriginNeedsTheEvictorKeyNotTheNodeOptimizerKey() {
        makeManual(cluster);
        send(cluster, item("NODE_DRAIN", "DRAIN", "evictor", "n1", FP_A, "ALLOWED", null));
        String id = proposals.findAll().get(0).getId();
        readiness.setEvictorLive(false);
        assertRefusal(() -> service.approve(ctx(cluster, user(5, "ann")), id, new ApproveRequest(0L, FP_A, null)),
                HttpStatus.CONFLICT, "K8S_CONTROLLER_NOT_READY");
    }

    // ------------------------------------------------------------------ workload path

    private String workloadProposal() {
        NewProposal np = new NewProposal(ProposalActionType.WORKLOAD_RESIZE, "IN_PLACE", ProposalService.SOURCE_BACKEND,
                "vertical-engine", "WORKLOAD", "shop/Deployment/api", null,
                "WORKLOAD_RESIZE:IN_PLACE:vertical-engine:shop/Deployment/api",
                "{\"namespace\":\"shop\",\"kind\":\"Deployment\",\"name\":\"api\",\"method\":\"IN_PLACE\",\"current\":[],\"recommended\":[]}",
                "{}", "ALLOWED", List.of(), null, null, false, FP_A, null);
        assertThat(service.upsertWorkload(cluster.getId(), 1L, np)).isEqualTo(ProposalService.UpsertResult.ACCEPTED);
        return proposals.findAll().get(0).getId();
    }

    @Test
    void aWorkloadApprovalDispatchesToTheJavaExecutorAndNeedsNoAgentHeartbeat() {
        makeManual(cluster);
        clusters.recordPolicyServed(cluster.getId(), null, null);
        clusters.recordAgentFeatures(cluster.getId(), null, null, null);
        String id = workloadProposal();
        DecisionResult r = service.approve(ctx(cluster, user(5, "ann")), id, new ApproveRequest(0L, FP_A, null));
        assertThat(r.dispatchWorkload()).isTrue();
        assertThat(row(id).getStatus()).isEqualTo("DISPATCHED");
        assertThat(row(id).getDispatchAttempts()).isEqualTo(1);

        service.undoDispatch(cluster.getId(), id);
        assertThat(row(id).getStatus()).isEqualTo("APPROVED");
        assertThat(row(id).getDispatchAttempts()).isZero();
    }

    @Test
    void aWorkloadApprovalRunsTheAccountScopeGateAndTheDisabledModeCheck() {
        makeManual(cluster);
        String id = workloadProposal();
        when(access.writeDecision(any(), any())).thenReturn(
                new WriteDecision(List.of(K8sAutomationReason.READ_ONLY_CONNECTION), null));
        assertThatThrownBy(() -> service.approve(ctx(cluster, user(5, "ann")), id, new ApproveRequest(0L, FP_A, null)))
                .isInstanceOf(K8sAutomationRefusedException.class);
        assertThat(row(id).getStatus()).isEqualTo("PENDING");

        when(access.writeDecision(any(), any())).thenReturn(new WriteDecision(List.of(), null));
        when(autoscaling.storedAutoscalerMode(any())).thenReturn("disabled");
        assertRefusal(() -> service.approve(ctx(cluster, user(5, "ann")), id, new ApproveRequest(0L, FP_A, null)),
                HttpStatus.CONFLICT, "K8S_MODE_DISABLED");
    }

    @Test
    void theWorkloadProposerCannotWriteIntoANonManualCluster() {
        NewProposal np = new NewProposal(ProposalActionType.WORKLOAD_RESIZE, "IN_PLACE", ProposalService.SOURCE_BACKEND,
                "vertical-engine", "WORKLOAD", "a/b/c", null, "k", "{}", "{}", "ALLOWED", List.of(), null, null,
                false, FP_A, null);
        assertThat(service.upsertWorkload(cluster.getId(), 1L, np)).isEqualTo(ProposalService.UpsertResult.NOT_MANUAL);
        assertThat(proposals.count()).isZero();
    }

    // ------------------------------------------------------------------ claim

    @Test
    void claimReturnsTheOldestApprovedNodeProposalOfTheCallersOwnClusterOnly() {
        makeManual(cluster);
        makeManual(otherCluster);
        String first = approved("n1");
        clock.advance(Duration.ofSeconds(5));
        approved("n2");

        Optional<ApprovedAction> mine = service.claim(reload(cluster), new ClaimRequest("c-1"));
        assertThat(mine).isPresent();
        ApprovedAction a = mine.get();
        assertThat(a.proposalId()).isEqualTo(first);
        assertThat(a.actionType()).isEqualTo("NODE_DRAIN");
        assertThat(a.target().name()).isEqualTo("n1");
        assertThat(a.target().providerId()).isEqualTo("aws:///a/i-n1");
        assertThat(a.target().nodeGroup()).isEqualTo("ng-1");
        assertThat(a.dwellSeconds()).isNull();
        assertThat(a.approvedBy().name()).isEqualTo("ann");
        assertThat(a.dryRunAtProposal()).isFalse();
        assertThat(row(first).getStatus()).isEqualTo("DISPATCHED");
        assertThat(row(first).getDispatchAttempts()).isEqualTo(1);

        // another cluster's key never sees this cluster's approved rows
        assertThat(service.claim(reload(otherCluster), new ClaimRequest("c-1"))).isEmpty();
    }

    @Test
    void whileOneNodeActionIsDispatchedANewClaimReturnsNothingAndNeverRedeliversABody() {
        makeManual(cluster);
        approved("n1");
        approved("n2");
        assertThat(service.claim(reload(cluster), new ClaimRequest("c-1"))).isPresent();
        assertThat(service.claim(reload(cluster), new ClaimRequest("c-2"))).isEmpty();
        assertThat(service.claim(reload(cluster), new ClaimRequest("c-3"))).isEmpty();
    }

    @Test
    void aConsolidationClaimCarriesTheDwellFromTheRow() {
        makeManual(cluster);
        send(cluster, item("NODE_CONSOLIDATE", "EMPTY", "consolidation", "n1", FP_A, "ALLOWED", 120));
        String id = proposals.findAll().get(0).getId();
        clock.advance(Duration.ofMinutes(3));
        clusters.recordPolicyServed(cluster.getId(), "MANUAL", clock.instant());
        service.approve(ctx(cluster, user(5, "ann")), id, new ApproveRequest(0L, FP_A, null));
        ApprovedAction a = service.claim(reload(cluster), new ClaimRequest("c")).orElseThrow();
        assertThat(a.dwellSeconds()).isEqualTo(120);
        assertThat(a.firstSeenAt()).isEqualTo(T0);
    }

    @Test
    void claimRefusesOutsideManualModeWhilePausedAndWhenTheWindowOrKeyIsGone() {
        makeManual(cluster);
        String id = approved("n1");

        when(autoscaling.storedAutoscalerMode(any())).thenReturn("disabled");
        assertRefusal(() -> service.claim(reload(cluster), new ClaimRequest("c")), HttpStatus.CONFLICT, "K8S_MODE_DISABLED");
        when(autoscaling.storedAutoscalerMode(any())).thenReturn("full");

        readiness.setNodeOptimizerActions(false);
        assertThat(service.claim(reload(cluster), new ClaimRequest("c"))).isEmpty();
        readiness.setNodeOptimizerActions(true);

        clock.advance(Duration.ofMinutes(16));
        assertThat(service.claim(reload(cluster), new ClaimRequest("c"))).as("claim window passed").isEmpty();
        assertThat(row(id).getStatus()).isEqualTo("APPROVED");

        clusters.setApprovalMode(cluster.getId(), "AUTONOMOUS", clock.instant(), 1L);
        assertRefusal(() -> service.claim(reload(cluster), new ClaimRequest("c")), HttpStatus.CONFLICT, "NOT_MANUAL");
    }

    @Test
    void claimIsQuietWhenTheOwningAccountBlocksAutomation() {
        makeManual(cluster);
        approved("n1");
        when(access.agentPolicyBlock(any())).thenReturn(K8sAutomationReason.READ_ONLY_CONNECTION);
        assertThat(service.claim(reload(cluster), new ClaimRequest("c"))).isEmpty();
    }

    // ------------------------------------------------------------------ outcome

    private String dispatched(String node) {
        String id = approved(node);
        service.claim(reload(cluster), new ClaimRequest("c"));
        return id;
    }

    @Test
    void outcomesMapToTerminalStatesAndAreIdempotent() {
        makeManual(cluster);
        String id = dispatched("n1");
        var verified = new OutcomeRequest(1, id, "succeeded", true, false, "EXECUTION", "", "proposal-" + id, null, null, null);
        assertThat(service.outcome(reload(cluster), id, verified).status()).isEqualTo("EXECUTED");
        AutomationProposal p = row(id);
        assertThat(p.getStatus()).isEqualTo("EXECUTED");
        assertThat(p.getOutcomeStatus()).isEqualTo("succeeded");
        assertThat(p.getOutcomeVerified()).isTrue();
        assertThat(p.getFinishedAt()).isNotNull();
        assertThat(p.getCorrelationId()).isEqualTo("proposal-" + id);

        // a replay of the same outcome is a 200, a different one is NOT_DISPATCHED
        assertThat(service.outcome(reload(cluster), id, verified).status()).isEqualTo("EXECUTED");
        assertRefusal(() -> service.outcome(reload(cluster), id, outcome(id, "failed")), HttpStatus.CONFLICT, "NOT_DISPATCHED");
    }

    @Test
    void eachOutcomeStatusLandsInItsStatus() {
        makeManual(cluster);
        String[][] cases = {{"simulated", "EXECUTED"}, {"failed", "FAILED"}, {"aborted", "FAILED"},
                {"skipped", "FAILED"}, {"stale", "STALE"}, {"refused", "REFUSED"}};
        int n = 0;
        for (String[] c : cases) {
            String id = dispatched("node-" + n++);
            service.outcome(reload(cluster), id, outcome(id, c[0]));
            AutomationProposal p = row(id);
            assertThat(p.getStatus()).as(c[0]).isEqualTo(c[1]);
            if (c[1].equals("REFUSED")) {
                assertThat(p.getRefusalStage()).isEqualTo("EXECUTION");
            }
            clock.advance(Duration.ofMinutes(1));
            clusters.recordPolicyServed(cluster.getId(), "MANUAL", clock.instant());
        }
    }

    @Test
    void notStartedRequeuesOnceThenFails() {
        makeManual(cluster);
        String id = dispatched("n1");
        assertThat(service.outcome(reload(cluster), id, outcome(id, "not-started")).status()).isEqualTo("APPROVED");
        assertThat(row(id).getStatus()).isEqualTo("APPROVED");

        assertThat(service.claim(reload(cluster), new ClaimRequest("c"))).isPresent();
        assertThat(row(id).getDispatchAttempts()).isEqualTo(2);
        assertThat(service.outcome(reload(cluster), id, outcome(id, "not-started")).status()).isEqualTo("FAILED");
        assertThat(row(id).getStatusReason()).isEqualTo("NOT_STARTED");
        assertThat(row(id).getOutcomeStatus()).isEqualTo("not-started");
    }

    @Test
    void notStartedAfterTheApprovalWindowFailsInsteadOfRequeueing() {
        makeManual(cluster);
        String id = dispatched("n1");
        clock.advance(Duration.ofMinutes(20));
        assertThat(service.outcome(reload(cluster), id, outcome(id, "not-started")).status()).isEqualTo("FAILED");
    }

    @Test
    void runningOnlyRefreshesProgressWhileDispatched() {
        makeManual(cluster);
        String id = dispatched("n1");
        clock.advance(Duration.ofMinutes(3));
        assertThat(service.outcome(reload(cluster), id, outcome(id, "running")).status()).isEqualTo("DISPATCHED");
        assertThat(row(id).getLastProgressAt()).isEqualTo(clock.instant());
        assertThat(row(id).getStatus()).isEqualTo("DISPATCHED");

        service.outcome(reload(cluster), id, outcome(id, "succeeded"));
        assertRefusal(() -> service.outcome(reload(cluster), id, outcome(id, "running")), HttpStatus.CONFLICT, "NOT_DISPATCHED");
    }

    @Test
    void aClusterCannotReportAnOutcomeForAnotherClustersProposal() {
        makeManual(cluster);
        makeManual(otherCluster);
        String id = dispatched("n1");
        assertRefusal(() -> service.outcome(reload(otherCluster), id, outcome(id, "succeeded")), HttpStatus.NOT_FOUND, "NOT_FOUND");
        assertThat(row(id).getStatus()).isEqualTo("DISPATCHED");
    }

    @Test
    void outcomeValidation() {
        makeManual(cluster);
        String id = dispatched("n1");
        var c = reload(cluster);
        assertRefusal(() -> service.outcome(c, id, new OutcomeRequest(1, "other", "succeeded", true, false, "EXECUTION", "",
                "proposal-" + id, null, null, null)), HttpStatus.BAD_REQUEST, "ID_MISMATCH");
        assertRefusal(() -> service.outcome(c, id, new OutcomeRequest(1, id, "succeeded", true, false, "EXECUTION", "",
                "proposal-other", null, null, null)), HttpStatus.BAD_REQUEST, "BAD_OPERATION_ID");
        assertRefusal(() -> service.outcome(c, id, new OutcomeRequest(1, id, "weird", true, false, "EXECUTION", "",
                "proposal-" + id, null, null, null)), HttpStatus.BAD_REQUEST, "BAD_STATUS");
        assertRefusal(() -> service.outcome(c, id, new OutcomeRequest(1, id, "succeeded", true, false, "NOPE", "",
                "proposal-" + id, null, null, null)), HttpStatus.BAD_REQUEST, "BAD_STAGE");
        assertRefusal(() -> service.outcome(c, id, new OutcomeRequest(1, id, "succeeded", true, false, "EXECUTION",
                "x".repeat(1001), "proposal-" + id, null, null, null)), HttpStatus.BAD_REQUEST, "BAD_REASON");
        assertRefusal(() -> service.outcome(c, id, new OutcomeRequest(2, id, "succeeded", true, false, "EXECUTION", "",
                "proposal-" + id, null, null, null)), HttpStatus.BAD_REQUEST, "UNSUPPORTED_SCHEMA");
        var tooMany = json.createArrayNode();
        for (int i = 0; i < 51; i++) {
            tooMany.addObject();
        }
        assertRefusal(() -> service.outcome(c, id, new OutcomeRequest(1, id, "succeeded", true, false, "EXECUTION", "",
                "proposal-" + id, tooMany, null, null)), HttpStatus.BAD_REQUEST, "BAD_ACTIONS");
        assertThat(row(id).getStatus()).isEqualTo("DISPATCHED");
    }

    @Test
    void dispatchedListsOnlyThisClustersNodeRowsWithTheStoredDryRun() {
        makeManual(cluster);
        makeManual(otherCluster);
        String id = dispatched("n1");
        var list = service.dispatched(reload(cluster)).items();
        assertThat(list).singleElement().satisfies(i -> {
            assertThat(i.id()).isEqualTo(id);
            assertThat(i.operationId()).isEqualTo("proposal-" + id);
            assertThat(i.targetRef()).isEqualTo("n1");
            assertThat(i.targetIdentity()).isEqualTo("uid-n1");
            assertThat(i.dryRun()).isFalse();
        });
        assertThat(service.dispatched(reload(otherCluster)).items()).isEmpty();
    }

    // ------------------------------------------------------------------ approval mode (AC-B2, B11)

    @Test
    void manualIsRefusedUntilTheKeyIsReadyAndTheAgentAdvertisedManualV1() {
        var ownerCtx = ctx(cluster, user(5, "ann"));
        readiness.setManualApproval(false);
        assertRefusal(() -> service.setApprovalMode(ownerCtx, "MANUAL"), HttpStatus.CONFLICT, "K8S_CONTROLLER_NOT_READY");
        readiness.setManualApproval(true);

        assertRefusal(() -> service.setApprovalMode(ownerCtx, "MANUAL"), HttpStatus.CONFLICT, "K8S_AGENT_TOO_OLD");
        clusters.recordAgentFeatures(cluster.getId(), "manual-v1", clock.instant(), 1200);
        clock.advance(Duration.ofHours(3));
        assertRefusal(() -> service.setApprovalMode(ctx(cluster, user(5, "ann")), "MANUAL"), HttpStatus.CONFLICT, "K8S_AGENT_TOO_OLD");

        clusters.recordAgentFeatures(cluster.getId(), "other,manual-v1", clock.instant(), 1200);
        var res = service.setApprovalMode(ctx(cluster, user(5, "ann")), "MANUAL");
        assertThat(res.mode()).isEqualTo("MANUAL");
        assertThat(res.effective()).isEqualTo("switching");
        assertThat(reload(cluster).getAutomationApprovalMode()).isEqualTo("MANUAL");
        assertThat(reload(cluster).getApprovalModeChangedBy()).isEqualTo(5L);
    }

    @Test
    void manualNeedsAClusterScopedAutomationKey() {
        KubernetesClusterConfig legacy = newCluster("legacy");
        legacy.setAutomationAgentApiKey(null);
        clusters.saveAndFlush(legacy);
        clusters.recordAgentFeatures(legacy.getId(), "manual-v1", clock.instant(), 1200);
        assertRefusal(() -> service.setApprovalMode(ctx(legacy, user(5, "ann")), "MANUAL"), HttpStatus.CONFLICT, "AGENT_KEY_LEGACY");
    }

    @Test
    void anInvalidModeIsABadRequest() {
        assertRefusal(() -> service.setApprovalMode(ctx(cluster, user(5, "ann")), "MAYBE"), HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        assertRefusal(() -> service.setApprovalMode(ctx(cluster, user(5, "ann")), null), HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
    }

    @Test
    void switchingBackToAutonomousMakesOpenProposalsStaleButLetsDispatchedOnesFinish() {
        makeManual(cluster);
        String disp = dispatched("dispatched-node");
        String pend = pending("pending-node");
        String appr = approved("approved-node");

        var res = service.setApprovalMode(ctx(cluster, user(5, "ann")), "AUTONOMOUS");
        assertThat(res.mode()).isEqualTo("AUTONOMOUS");
        assertThat(row(pend).getStatus()).isEqualTo("STALE");
        assertThat(row(pend).getStatusReason()).isEqualTo("MODE_CHANGED");
        assertThat(row(appr).getStatus()).isEqualTo("STALE");
        assertThat(row(disp).getStatus()).isEqualTo("DISPATCHED");
        // reconcile/outcome keep working by proposal id regardless of the cluster's current mode
        assertThat(service.outcome(reload(cluster), disp, outcome(disp, "succeeded")).status()).isEqualTo("EXECUTED");
    }

    // ------------------------------------------------------------------ effective (3.5)

    @Test
    void effectiveIsActiveSwitchingOrUnknown() {
        makeManual(cluster);
        assertThat(service.effective(reload(cluster), clock.instant())).isEqualTo("active");
        clock.advance(Duration.ofSeconds(90));
        assertThat(service.effective(reload(cluster), clock.instant())).isEqualTo("active");
        clock.advance(Duration.ofSeconds(110)); // t = 200 s with no further serve, agent still alive
        assertThat(service.effective(reload(cluster), clock.instant())).isEqualTo("switching");
        clock.advance(Duration.ofMinutes(11));
        assertThat(service.effective(reload(cluster), clock.instant())).isEqualTo("unknown");
    }

    @Test
    void aMovedModeNotYetServedIsSwitchingAndAnAutonomousNeverManualClusterIsActive() {
        assertThat(service.effective(reload(cluster), clock.instant())).isEqualTo("active");
        clusters.recordAgentFeatures(cluster.getId(), "manual-v1", clock.instant(), 1200);
        clusters.setApprovalMode(cluster.getId(), "MANUAL", clock.instant(), 1L);
        assertThat(service.effective(reload(cluster), clock.instant())).isEqualTo("switching");
    }

    // ------------------------------------------------------------------ list

    @Test
    void listIsNewestFirstFiltersByStatusPagesWithACursorAndCounts() {
        makeManual(cluster);
        for (int i = 0; i < 5; i++) {
            pending("node-" + i);
            clock.advance(Duration.ofSeconds(1));
        }
        String firstId = proposals.findAll().stream().filter(p -> p.getTargetRef().equals("node-0")).findFirst().orElseThrow().getId();
        service.reject(ctx(cluster, user(5, "ann")), firstId, new RejectRequest(0L, null));

        ProposalListDto page1 = service.list(reload(cluster), null, 2, null);
        assertThat(page1.state()).isEqualTo("OK");
        assertThat(page1.items()).hasSize(2);
        assertThat(page1.items().get(0).targetRef()).isEqualTo("node-4");
        assertThat(page1.nextCursor()).isNotNull();
        assertThat(page1.counts().pending()).isEqualTo(4);
        assertThat(page1.approvalMode()).isEqualTo("MANUAL");
        assertThat(page1.effective()).isEqualTo("active");
        assertThat(page1.manual().available()).isTrue();
        assertThat(page1.manual().workloadProposals()).isTrue();
        assertThat(page1.lastAgentCheckIn()).isNotNull();

        ProposalListDto page2 = service.list(reload(cluster), null, 2, page1.nextCursor());
        assertThat(page2.items()).extracting(i -> i.targetRef()).containsExactly("node-2", "node-1");
        ProposalListDto page3 = service.list(reload(cluster), null, 2, page2.nextCursor());
        assertThat(page3.items()).extracting(i -> i.targetRef()).containsExactly("node-0");
        assertThat(page3.nextCursor()).isNull();

        assertThat(service.list(reload(cluster), "REJECTED", null, null).items()).hasSize(1);
        assertThat(service.list(reload(otherCluster), null, null, null).items()).isEmpty();
    }

    @Test
    void listValidatesItsParameters() {
        assertRefusal(() -> service.list(reload(cluster), null, 0, null), HttpStatus.BAD_REQUEST, "BAD_LIMIT");
        assertRefusal(() -> service.list(reload(cluster), null, 201, null), HttpStatus.BAD_REQUEST, "BAD_LIMIT");
        assertRefusal(() -> service.list(reload(cluster), "NOPE", null, null), HttpStatus.BAD_REQUEST, "BAD_STATUS");
        assertRefusal(() -> service.list(reload(cluster), null, null, "!!!"), HttpStatus.BAD_REQUEST, "BAD_CURSOR");
    }

    @Test
    void anUnreadableRowMakesTheWholeListUnreadableAndNeverPartial() {
        makeManual(cluster);
        String good = pending("good");
        String bad = pending("bad");
        AutomationProposal p = proposals.findById(bad).orElseThrow();
        p.setParamsJson("{not json");
        proposals.saveAndFlush(p);

        ProposalListDto list = service.list(reload(cluster), null, null, null);
        assertThat(list.state()).isEqualTo("UNREADABLE");
        assertThat(list.items()).isEmpty();
        assertThat(list.nextCursor()).isNull();
        assertThat(list.counts().pending()).as("counts come from SQL and stay correct").isEqualTo(2);
        assertRefusal(() -> service.approve(ctx(cluster, user(5, "ann")), bad, new ApproveRequest(0L, FP_A, null)),
                HttpStatus.CONFLICT, "UNREADABLE");
        assertThat(good).isNotBlank();
    }

    // ------------------------------------------------------------------ sweeper (AC-B10)

    @Test
    void theSweeperExpiresPendingAndUnclaimedApprovedRows() {
        makeManual(cluster);
        props.getLastSeenGraceMinutes().setAgent(100_000); // isolate the TTL rules from the not-re-seen rule
        String pend = pending("p");
        String appr = approved("a");
        clock.advance(Duration.ofMinutes(16));
        ProposalService.SweepResult r = service.sweep();
        assertThat(r.expiredApproved()).isEqualTo(1);
        assertThat(row(appr).getStatus()).isEqualTo("EXPIRED");
        assertThat(row(pend).getStatus()).isEqualTo("PENDING");
        clock.advance(Duration.ofMinutes(110));
        clusters.recordPolicyServed(cluster.getId(), "MANUAL", clock.instant());
        service.sweep();
        assertThat(row(pend).getStatus()).isEqualTo("EXPIRED");
    }

    @Test
    void aDispatchedRowIsFailedOnlyPastItsLeaseAndOnlyWhenTheAgentWentSilent() {
        makeManual(cluster);
        String id = dispatched("n1");

        clock.advance(Duration.ofMinutes(29));
        assertThat(service.sweep().failedDispatched()).isZero();

        clock.advance(Duration.ofMinutes(2)); // 31 minutes, no progress ever
        assertThat(service.sweep().failedDispatched()).isEqualTo(1);
        assertThat(row(id).getStatus()).isEqualTo("FAILED");
        assertThat(row(id).getStatusReason()).isEqualTo("LEASE_EXPIRED");
    }

    @Test
    void aRowWhoseAgentReportedRunningRecentlyIsNeverFailedOnTimeAlone() {
        makeManual(cluster);
        String id = dispatched("n1");
        clock.advance(Duration.ofMinutes(33));
        service.outcome(reload(cluster), id, outcome(id, "running"));
        clock.advance(Duration.ofMinutes(2));
        assertThat(service.sweep().failedDispatched()).isZero();
        assertThat(row(id).getStatus()).isEqualTo("DISPATCHED");

        clock.advance(Duration.ofMinutes(15)); // progress now 17 minutes old
        assertThat(service.sweep().failedDispatched()).isEqualTo(1);
    }

    @Test
    void aLongDrainTimeoutStretchesTheLeaseAndASecondClaimStaysBlocked() {
        makeManual(cluster);
        clusters.recordAgentFeatures(cluster.getId(), "manual-v1", clock.instant(), 3600);
        assertThat(props.effectiveLeaseMinutes(3600)).isEqualTo(70);
        String id = dispatched("n1");
        approved("n2");
        clock.advance(Duration.ofMinutes(40));
        assertThat(service.sweep().failedDispatched()).isZero();
        assertThat(service.claim(reload(cluster), new ClaimRequest("c"))).isEmpty();
        assertThat(row(id).getStatus()).isEqualTo("DISPATCHED");
        assertThat(props.effectiveLeaseMinutes(null)).isEqualTo(30);
    }

    @Test
    void rowsTheirSourceStoppedResendingGoStaleButExecutionRefusalsAreUntouched() {
        makeManual(cluster);
        String pend = pending("p");
        send(cluster, item("NODE_DRAIN", "DRAIN", "node-optimizer", "r", FP_B, "REFUSED", null));
        String refused = proposals.findAll().stream().filter(x -> x.getStatus().equals("REFUSED")).findFirst().orElseThrow().getId();
        clock.advance(Duration.ofMinutes(11));
        ProposalService.SweepResult r = service.sweep();
        assertThat(r.staleNotReseen()).isEqualTo(2);
        assertThat(row(pend).getStatus()).isEqualTo("STALE");
        assertThat(row(pend).getStatusReason()).isEqualTo("NOT_RESEEN");
        assertThat(row(refused).getStatus()).isEqualTo("STALE");
    }

    @Test
    void historyOlderThanTheRetentionIsPruned() {
        makeManual(cluster);
        String id = pending("n1");
        service.reject(ctx(cluster, user(5, "ann")), id, new RejectRequest(0L, null));
        clock.advance(Duration.ofDays(91));
        assertThat(service.sweep().deleted()).isEqualTo(1);
        assertThat(proposals.count()).isZero();
    }
}
