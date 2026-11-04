package com.xammer.cloud.service.automation.proposal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.config.K8sAutomationReadinessProperties;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.dto.autoscaling.AgentProposalDtos.Gate;
import com.xammer.cloud.dto.autoscaling.AgentProposalDtos.Item;
import com.xammer.cloud.dto.autoscaling.AgentProposalDtos.Refusal;
import com.xammer.cloud.dto.autoscaling.AgentProposalDtos.Target;
import com.xammer.cloud.repository.ApprovalModeProjectionRepository;
import com.xammer.cloud.repository.AutomationProposalEventRepository;
import com.xammer.cloud.repository.AutomationProposalRepository;
import com.xammer.cloud.repository.JpaSliceTestConfig;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.K8sAutomationAccessService;
import com.xammer.cloud.security.K8sAutomationAccessService.WriteDecision;
import com.xammer.cloud.security.K8sAutomationRequestContext;
import com.xammer.cloud.service.EksAutoscalingService;
import com.xammer.cloud.service.automation.workload.WorkloadAutoscalingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Wiring for the Manual-mode service tests: real H2 repositories and a real {@link ProposalService}
 * on a movable clock, with the collaborators outside this feature mocked. The cluster-row lock is a
 * real {@code SELECT ... FOR UPDATE}; true cross-connection contention is not testable on H2 (the
 * containers test covers it).
 */
@DataJpaTest
@ContextConfiguration(classes = JpaSliceTestConfig.class)
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=",
        "spring.flyway.enabled=false"
})
abstract class ProposalServiceTestBase {

    static final String FP_A = "sha256:" + "a".repeat(64);
    static final String FP_B = "sha256:" + "b".repeat(64);
    static final Instant T0 = Instant.parse("2026-11-01T10:00:00Z");

    @Autowired
    KubernetesClusterConfigRepository clusters;
    @Autowired
    AutomationProposalRepository proposals;
    @Autowired
    AutomationProposalEventRepository events;
    @Autowired
    ApprovalModeProjectionRepository modeProjection;
    @Autowired
    jakarta.persistence.EntityManager em;

    MovableClock clock;
    ManualProperties props;
    K8sAutomationReadinessProperties readiness;
    EksAutoscalingService autoscaling;
    K8sAutomationAccessService access;
    WorkloadAutoscalingProperties workloadProps;
    ProposalService service;
    KubernetesClusterConfig cluster;
    KubernetesClusterConfig otherCluster;
    final ObjectMapper json = new ObjectMapper();

    /** A clock the test moves forward. */
    static final class MovableClock extends Clock {
        private Instant now;

        MovableClock(Instant start) {
            this.now = start;
        }

        void advance(java.time.Duration d) {
            now = now.plus(d);
        }

        void set(Instant i) {
            now = i;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @BeforeEach
    void wire() {
        // The embedded H2 is shared across slice tests; start from empty proposal tables (rolled back with the test).
        events.deleteAll();
        proposals.deleteAll();
        clock = new MovableClock(T0);
        props = new ManualProperties();
        readiness = new K8sAutomationReadinessProperties();
        readiness.setManualApproval(true);
        readiness.setNodeOptimizerActions(true);
        readiness.setEvictorLive(true);
        readiness.setConsolidationActions(true);
        readiness.setRebalancer(true);
        readiness.setVerticalRightsizing(true);
        autoscaling = mock(EksAutoscalingService.class);
        when(autoscaling.storedAutoscalerMode(any())).thenReturn("full");
        access = mock(K8sAutomationAccessService.class);
        when(access.agentPolicyBlock(any())).thenReturn(null);
        when(access.writeDecision(any(), any())).thenReturn(new WriteDecision(List.of(), null));
        workloadProps = mock(WorkloadAutoscalingProperties.class);
        when(workloadProps.isAutoApplyPermitted()).thenReturn(true);
        service = new ProposalService(proposals, events, clusters, new ApprovalModeReader(modeProjection), props,
                readiness, autoscaling, access, workloadProps, clock);
        cluster = newCluster("prod-eks");
        otherCluster = newCluster("other-eks");
    }

    KubernetesClusterConfig newCluster(String name) {
        KubernetesClusterConfig c = new KubernetesClusterConfig();
        c.setCloudAccountId(1L);
        c.setClusterName(name);
        c.setRegion("us-east-1");
        c.setAutomationAgentApiKey("automation-key-" + name);
        return clusters.saveAndFlush(c);
    }

    /** Puts the cluster in MANUAL with a fresh manual-v1 leader heartbeat, as a real rollout would. */
    void makeManual(KubernetesClusterConfig c) {
        clusters.setApprovalMode(c.getId(), "MANUAL", clock.instant(), 1L);
        clusters.recordAgentFeatures(c.getId(), "manual-v1", clock.instant(), 1200);
        clusters.recordPolicyServed(c.getId(), "MANUAL", clock.instant());
    }

    /**
     * A fresh read of the cluster row. The Manual-mode writers are bulk updates that do not touch the
     * persistence context, and in production every request loads its own entity, so the test clears it.
     */
    KubernetesClusterConfig reload(KubernetesClusterConfig c) {
        em.flush();
        em.clear();
        return clusters.findById(c.getId()).orElseThrow();
    }

    ClientUserDetails user(long id, String name) {
        ClientUserDetails u = mock(ClientUserDetails.class);
        when(u.getId()).thenReturn(id);
        when(u.getUsername()).thenReturn(name);
        return u;
    }

    K8sAutomationRequestContext ctx(KubernetesClusterConfig c, ClientUserDetails u) {
        return new K8sAutomationRequestContext(u, List.of(new CloudAccount()), reload(c));
    }

    Item drainItem(String node, String fingerprint) {
        return item("NODE_DRAIN", "DRAIN", "node-optimizer", node, fingerprint, "ALLOWED", null);
    }

    Item item(String type, String op, String origin, String node, String fingerprint, String verdict, Integer dwell) {
        try {
            return new Item(type + ":" + op + ":" + origin + ":" + node, type, op, origin,
                    new Target("NODE", node, "uid-" + node, "aws:///a/i-" + node, "ng-1"),
                    json.readTree("{\"trigger\":\"idle-drain\",\"unschedulableAtProposal\":false}"),
                    verdict,
                    "REFUSED".equals(verdict) ? new Refusal(List.of("pdb-no-disruptions:ns/x"), "pdb") : new Refusal(List.of(), ""),
                    new Gate("live", "all gates open", false), fingerprint, dwell,
                    json.readTree("{\"fit\":{\"drainable\":true}}"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    com.xammer.cloud.dto.autoscaling.AgentProposalDtos.BatchRequest batch(Item... items) {
        return new com.xammer.cloud.dto.autoscaling.AgentProposalDtos.BatchRequest(1, "c-1", clock.instant(),
                List.of(items), null);
    }

    /** Sends a batch and advances the clock past the 2 s rate limit so the next one is accepted. */
    com.xammer.cloud.dto.autoscaling.AgentProposalDtos.BatchResponse send(KubernetesClusterConfig c, Item... items) {
        var response = service.acceptBatch(reload(c), batch(items));
        clock.advance(java.time.Duration.ofSeconds(3));
        return response;
    }

    String onlyPendingId(KubernetesClusterConfig c) {
        return proposals.page(c.getId(), List.of("PENDING"), Instant.parse("9999-12-31T00:00:00Z"), "\uffff",
                org.springframework.data.domain.PageRequest.of(0, 5)).get(0).getId();
    }
}
