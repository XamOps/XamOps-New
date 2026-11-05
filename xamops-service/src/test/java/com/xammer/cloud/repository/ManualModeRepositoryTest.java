package com.xammer.cloud.repository;

import com.xammer.cloud.domain.AutomationProposal;
import com.xammer.cloud.domain.AutomationProposalEvent;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Build checks B1-B3 for Manual mode on H2 (design 3.2):
 *
 * <ul>
 *   <li>B1: the three HQL writers on {@code KubernetesClusterConfig} return 1 and really persist
 *       columns mapped {@code updatable = false} (if Hibernate ever ignored them, the fallback is native
 *       queries; this test is what would say so).</li>
 *   <li>B2: a stale-entity {@code save()}, exactly what the agent heartbeat does, leaves a MANUAL
 *       cluster MANUAL and never writes any of the eight guarded columns.</li>
 *   <li>B3: the TEXT columns are not truncated at 255 characters.</li>
 * </ul>
 *
 * The test methods run outside the test transaction so the interleaved transactions really commit.
 */
@DataJpaTest
@ContextConfiguration(classes = JpaSliceTestConfig.class)
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=",
        "spring.flyway.enabled=false"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ManualModeRepositoryTest {

    @Autowired
    KubernetesClusterConfigRepository clusters;
    @Autowired
    ApprovalModeProjectionRepository modeProjection;
    @Autowired
    AutomationProposalRepository proposals;
    @Autowired
    AutomationProposalEventRepository events;
    @Autowired
    PlatformTransactionManager transactionManager;

    /**
     * These tests commit (they run outside the test transaction on purpose), and the embedded H2 is shared by
     * every slice test with the same configuration, so they remove everything they created.
     */
    @AfterEach
    void cleanUp() {
        tx(() -> {
            events.deleteAll();
            proposals.deleteAll();
            clusters.deleteAll();
            return null;
        });
    }

    private <T> T tx(java.util.function.Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    private Long newCluster() {
        return tx(() -> {
            KubernetesClusterConfig c = new KubernetesClusterConfig();
            c.setCloudAccountId(1L);
            c.setClusterName("c-" + System.nanoTime());
            c.setRegion("us-east-1");
            return clusters.saveAndFlush(c).getId();
        });
    }

    @Test
    void aFreshClusterIsAutonomousAndPersistsWithoutViolatingNotNull() {
        Long id = newCluster();
        assertThat(tx(() -> modeProjection.approvalMode(id))).contains("AUTONOMOUS");
        assertThat(tx(() -> clusters.findById(id).orElseThrow().getAutomationApprovalMode())).isEqualTo("AUTONOMOUS");
    }

    @Test
    void theHqlWritersReturnOneAndPersistTheUpdatableFalseColumns() {
        Long id = newCluster();
        Instant at = Instant.parse("2026-11-01T10:00:00Z");

        assertThat(tx(() -> clusters.setApprovalMode(id, "MANUAL", at, 42L))).isEqualTo(1);
        assertThat(tx(() -> clusters.recordPolicyServed(id, "MANUAL", at.plusSeconds(5)))).isEqualTo(1);
        assertThat(tx(() -> clusters.recordAgentFeatures(id, "manual-v1", at.plusSeconds(10), 3600))).isEqualTo(1);

        KubernetesClusterConfig fresh = tx(() -> clusters.findById(id).orElseThrow());
        assertThat(fresh.getAutomationApprovalMode()).isEqualTo("MANUAL");
        assertThat(fresh.getApprovalModeChangedAt()).isEqualTo(at);
        assertThat(fresh.getApprovalModeChangedBy()).isEqualTo(42L);
        assertThat(fresh.getPolicyServedApprovalMode()).isEqualTo("MANUAL");
        assertThat(fresh.getPolicyServedAt()).isEqualTo(at.plusSeconds(5));
        assertThat(fresh.getAutomationAgentFeatures()).isEqualTo("manual-v1");
        assertThat(fresh.getAutomationAgentFeaturesSeenAt()).isEqualTo(at.plusSeconds(10));
        assertThat(fresh.getAutomationAgentDrainTimeoutSeconds()).isEqualTo(3600);
    }

    @Test
    void aStaleWholesaleSaveNeverOverwritesTheMode() {
        Long id = newCluster();
        // The heartbeat loads the entity...
        KubernetesClusterConfig stale = tx(() -> clusters.findById(id).orElseThrow());
        assertThat(stale.getAutomationApprovalMode()).isEqualTo("AUTONOMOUS");
        // ...a user flips the cluster to MANUAL in another transaction and commits...
        assertThat(tx(() -> clusters.setApprovalMode(id, "MANUAL", Instant.now(), 7L))).isEqualTo(1);
        // ...and the heartbeat's wholesale save() of its stale copy commits afterwards.
        stale.setAgentVersion("1.0.0-autoscaler");
        tx(() -> clusters.saveAndFlush(stale));

        assertThat(tx(() -> modeProjection.approvalMode(id))).contains("MANUAL");
        KubernetesClusterConfig after = tx(() -> clusters.findById(id).orElseThrow());
        assertThat(after.getAgentVersion()).as("the save still wrote its other columns").isEqualTo("1.0.0-autoscaler");
        assertThat(after.getApprovalModeChangedBy()).isEqualTo(7L);
        assertThat(stale.getAutomationApprovalMode()).as("the stale in-memory copy was never flushed for that column")
                .isEqualTo("AUTONOMOUS");
    }

    @Test
    void aStaleSaveAlsoLeavesTheServeAndFeatureColumnsAlone() {
        Long id = newCluster();
        KubernetesClusterConfig stale = tx(() -> clusters.findById(id).orElseThrow());
        Instant at = Instant.parse("2026-11-01T10:00:00Z");
        tx(() -> clusters.recordPolicyServed(id, "MANUAL", at));
        tx(() -> clusters.recordAgentFeatures(id, "manual-v1", at, null));
        tx(() -> clusters.saveAndFlush(stale));

        KubernetesClusterConfig after = tx(() -> clusters.findById(id).orElseThrow());
        assertThat(after.getPolicyServedApprovalMode()).isEqualTo("MANUAL");
        assertThat(after.getAutomationAgentFeatures()).isEqualTo("manual-v1");
    }

    @Test
    void textColumnsAreNotTruncated() {
        Long id = newCluster();
        String big = "x".repeat(100_000);
        String reason = "r".repeat(5_000);
        AutomationProposal p = new AutomationProposal();
        p.setId("p-text");
        p.setClusterConfigId(id);
        p.setActionType("NODE_DRAIN");
        p.setSource("AGENT");
        p.setOriginController("node-optimizer");
        p.setTargetKind("NODE");
        p.setTargetRef("n1");
        p.setDedupeKey("k");
        p.setParamsJson(big);
        p.setEvidenceJson(big);
        p.setVerdict("REFUSED");
        p.setRefusalReason(reason);
        p.setOutcomeJson(big);
        p.setGateJson(reason);
        p.setPlanFingerprint("sha256:" + "a".repeat(64));
        p.setStatus("REFUSED");
        p.setRefusalStage("PROPOSAL");
        p.setProposedAt(Instant.now());
        p.setFirstSeenAt(Instant.now());
        p.setLastSeenAt(Instant.now());
        p.setExpiresAt(Instant.now());
        tx(() -> proposals.saveAndFlush(p));
        AutomationProposalEvent e = new AutomationProposalEvent();
        e.setProposalId("p-text");
        e.setClusterConfigId(id);
        e.setAt(Instant.now());
        e.setActorType("SYSTEM");
        e.setEvent("PROPOSED");
        e.setDetail(reason);
        tx(() -> events.saveAndFlush(e));

        AutomationProposal read = tx(() -> proposals.findByIdAndClusterConfigId("p-text", id).orElseThrow());
        assertThat(read.getParamsJson()).hasSize(100_000);
        assertThat(read.getEvidenceJson()).hasSize(100_000);
        assertThat(read.getOutcomeJson()).hasSize(100_000);
        assertThat(read.getRefusalReason()).hasSize(5_000);
        List<AutomationProposalEvent> evs = tx(() ->
                events.findByProposalIdAndClusterConfigIdOrderByAtAscIdAsc("p-text", id));
        assertThat(evs).singleElement().satisfies(x -> assertThat(x.getDetail()).hasSize(5_000));
    }

    @Test
    void aProposalOfAnotherClusterIsInvisibleAndUntouchable() {
        Long a = newCluster();
        Long b = newCluster();
        AutomationProposal p = new AutomationProposal();
        p.setId("p-b");
        p.setClusterConfigId(b);
        p.setActionType("NODE_DRAIN");
        p.setSource("AGENT");
        p.setOriginController("node-optimizer");
        p.setTargetKind("NODE");
        p.setTargetRef("n1");
        p.setDedupeKey("k");
        p.setParamsJson("{}");
        p.setEvidenceJson("{}");
        p.setVerdict("ALLOWED");
        p.setPlanFingerprint("sha256:" + "a".repeat(64));
        p.setStatus("PENDING");
        p.setProposedAt(Instant.now());
        p.setFirstSeenAt(Instant.now());
        p.setLastSeenAt(Instant.now());
        p.setExpiresAt(Instant.now().plusSeconds(600));
        tx(() -> proposals.saveAndFlush(p));

        assertThat(tx(() -> proposals.findByIdAndClusterConfigId("p-b", a))).isEmpty();
        assertThat(tx(() -> proposals.reject("p-b", a, 0, 1L, "u", null, Instant.now()))).isZero();
        assertThat(tx(() -> proposals.approve("p-b", a, 0, p.getPlanFingerprint(), 1L, "u", null, Instant.now(),
                Instant.now().plusSeconds(60)))).isZero();
        assertThat(tx(() -> proposals.findByIdAndClusterConfigId("p-b", b).orElseThrow().getStatus()))
                .isEqualTo("PENDING");
    }
}
