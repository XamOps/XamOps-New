package com.xammer.cloud.service.automation.proposal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.config.K8sAutomationReadinessProperties;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.dto.agent.AgentPrincipal;
import com.xammer.cloud.dto.autoscaling.AutomationPolicyBundle;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.service.K8sAutomationPolicyNormalizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Layer 1 of Manual mode (design 3.3): AC-B1 (byte-identical when AUTONOMOUS), AC-B3 (the neutering table). */
class ManualModeServePolicyTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

    private K8sAutomationReadinessProperties readiness;
    private K8sAutomationPolicyNormalizer normalizer;
    private KubernetesClusterConfigRepository repo;
    private ManualModeServePolicy policy;
    private final AgentPrincipal automation = AgentPrincipal.cluster("111122223333", "prod", AgentPrincipal.AgentRole.AUTOMATION);

    @BeforeEach
    void setUp() {
        readiness = new K8sAutomationReadinessProperties();
        normalizer = new K8sAutomationPolicyNormalizer(readiness);
        repo = mock(KubernetesClusterConfigRepository.class);
        policy = new ManualModeServePolicy(normalizer,
                new ManualBookkeeping(repo, Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    private void everythingReady() {
        readiness.setEvictorLive(true);
        readiness.setNodeOptimizerActions(true);
        readiness.setSpotInterruptionActions(true);
        readiness.setConsolidationActions(true);
        readiness.setRebalancer(true);
        readiness.setVpaApply(true);
    }

    /** A bundle that is "everything on", as the agent would be served it before Manual existed. */
    private AutomationPolicyBundle liveBundle() {
        AutomationPolicyBundle b = normalizer.defaults();
        b.setAutoscalerMode("scoped");
        b.getEvictorPolicy().setAnalyseOnly(false);
        b.getNodeOptimizerPolicy().setAutoCordonIdleNodes(true);
        b.getNodeOptimizerPolicy().setAutoDrainIdleNodes(true);
        b.getNodeOptimizerPolicy().setSpotRatioTargetPct(30);
        b.getNodeOptimizerPolicy().setHandleSpotInterruptions(true);
        b.getConsolidationPolicy().setEnabled(true);
        b.getRebalancer().setEnabled(true);
        b.getRebalancer().setScheduleEnabled(true);
        b.getRebalancer().setStatus("running");
        b.getVpaManagerPolicy().setEnabled(true);
        b.getVpaManagerPolicy().setAnalyseOnly(false);
        b.getVpaManagerPolicy().setUpdateMode("Auto");
        return normalizer.forAgent(normalizer.toJson(normalizer.normalize(b, 1L)), 1L, true);
    }

    private KubernetesClusterConfig cluster(String mode) throws Exception {
        KubernetesClusterConfig c = new KubernetesClusterConfig();
        c.setId(1L);
        // updatable=false field: set through reflection exactly as Hibernate hydrates it
        var f = KubernetesClusterConfig.class.getDeclaredField("automationApprovalMode");
        f.setAccessible(true);
        f.set(c, mode);
        return c;
    }

    @Test
    void anAutonomousClusterIsServedExactlyTheNormalizersBytes() throws Exception {
        everythingReady();
        AutomationPolicyBundle bundle = liveBundle();
        String expected = normalizer.toJson(bundle);
        String served = policy.serve(cluster("AUTONOMOUS"), automation, bundle);
        assertThat(served).isEqualTo(expected);
        assertThat(JSON.readTree(served).has("approvalMode")).isFalse();
        assertThat(JSON.readTree(served).has("manualIntent")).isFalse();
        verifyNoInteractions(repo); // a cluster that was never Manual gets no bookkeeping write
    }

    @Test
    void aManualClusterIsServedTheNeuteredPolicyAndTheIntentCopy() throws Exception {
        everythingReady();
        JsonNode served = JSON.readTree(policy.serve(cluster("MANUAL"), automation, liveBundle()));

        assertThat(served.get("approvalMode").asText()).isEqualTo("manual");
        assertThat(served.get("autoscalerMode").asText()).as("passed through unchanged").isEqualTo("scoped");
        assertThat(served.at("/evictorPolicy/analyseOnly").asBoolean()).isTrue();
        assertThat(served.at("/nodeOptimizerPolicy/autoCordonIdleNodes").asBoolean()).isFalse();
        assertThat(served.at("/nodeOptimizerPolicy/autoDrainIdleNodes").asBoolean()).isFalse();
        assertThat(served.at("/nodeOptimizerPolicy/spotRatioTargetPct").asInt()).isZero();
        assertThat(served.at("/nodeOptimizerPolicy/handleSpotInterruptions").asBoolean()).isFalse();
        assertThat(served.at("/rebalancer/status").asText()).isEqualTo("idle");
        assertThat(served.at("/rebalancer/scheduleEnabled").asBoolean()).isFalse();
        assertThat(served.at("/rebalancer/enabled").asBoolean()).isFalse();
        assertThat(served.at("/consolidationPolicy/enabled").asBoolean()).isFalse();
        assertThat(served.at("/vpaManagerPolicy/updateMode").asText()).isEqualTo("Off");
        assertThat(served.at("/vpaManagerPolicy/analyseOnly").asBoolean()).isTrue();

        JsonNode intent = served.get("manualIntent");
        assertThat(intent.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("evictorPolicy", "nodeOptimizerPolicy", "rebalancer", "consolidationPolicy");
        assertThat(intent.at("/evictorPolicy/analyseOnly").asBoolean()).isFalse();
        assertThat(intent.at("/nodeOptimizerPolicy/autoDrainIdleNodes").asBoolean()).isTrue();
        assertThat(intent.at("/nodeOptimizerPolicy/autoCordonIdleNodes").asBoolean()).isTrue();
        assertThat(intent.at("/rebalancer/status").asText()).isEqualTo("running");
        assertThat(intent.at("/rebalancer/enabled").asBoolean()).isTrue();
        assertThat(intent.at("/consolidationPolicy/enabled").asBoolean()).isTrue();
        assertThat(intent.has("vpaManagerPolicy")).as("VPA is not proposable in v1").isFalse();
    }

    @Test
    void theIntentReflectsReadinessCoercionSoAnUnreadyKeyIsAlreadySafeInIt() throws Exception {
        everythingReady();
        readiness.setNodeOptimizerActions(false);
        readiness.setConsolidationActions(false);
        JsonNode served = JSON.readTree(policy.serve(cluster("MANUAL"), automation, liveBundle()));
        assertThat(served.at("/manualIntent/nodeOptimizerPolicy/autoDrainIdleNodes").asBoolean()).isFalse();
        assertThat(served.at("/manualIntent/nodeOptimizerPolicy/autoCordonIdleNodes").asBoolean()).isFalse();
        assertThat(served.at("/manualIntent/consolidationPolicy/enabled").asBoolean()).isFalse();
        assertThat(served.at("/manualIntent/evictorPolicy/analyseOnly").asBoolean()).isFalse();
    }

    @Test
    void neuteringDoesNotDependOnTheManualApprovalKeyBeingReady() throws Exception {
        everythingReady();
        readiness.setManualApproval(false);
        JsonNode served = JSON.readTree(policy.serve(cluster("MANUAL"), automation, liveBundle()));
        assertThat(served.get("approvalMode").asText()).isEqualTo("manual");
        assertThat(served.at("/nodeOptimizerPolicy/autoDrainIdleNodes").asBoolean()).isFalse();
    }

    @Test
    void anUnknownStoredModeIsTreatedAsManualAndNeverAsAutonomous() throws Exception {
        everythingReady();
        JsonNode served = JSON.readTree(policy.serve(cluster("SOMETHING_ELSE"), automation, liveBundle()));
        assertThat(served.get("approvalMode").asText()).isEqualTo("manual");
        assertThat(served.at("/nodeOptimizerPolicy/autoDrainIdleNodes").asBoolean()).isFalse();
    }

    @Test
    void aSectionOmittedFromTheStoredBundleIsStillNeutered() {
        String neutered = policy.neuter("{\"autoscalerMode\":\"full\"}");
        assertThat(neutered).contains("\"approvalMode\":\"manual\"").contains("\"analyseOnly\":true")
                .contains("\"autoDrainIdleNodes\":false").contains("\"manualIntent\":{}");
    }

    // ------------------------------------------------------------------ serve bookkeeping

    @Test
    void servedBookkeepingIsWrittenOnlyForAnAutomationPrincipalOfAnEverManualCluster() throws Exception {
        everythingReady();
        AutomationPolicyBundle b = liveBundle();
        policy.serve(cluster("MANUAL"), automation, b);
        verify(repo).recordPolicyServed(1L, "MANUAL", NOW);

        org.mockito.Mockito.reset(repo);
        policy.serve(cluster("MANUAL"), AgentPrincipal.cluster("111122223333", "prod"), b); // data key
        policy.serve(cluster("MANUAL"), AgentPrincipal.account("111122223333"), b);
        policy.serve(cluster("MANUAL"), AgentPrincipal.legacyGlobal(), b);
        verifyNoInteractions(repo);
    }

    @Test
    void servedBookkeepingIsThrottledToOneWritePer30SecondsUnlessTheValueChanged() throws Exception {
        everythingReady();
        AutomationPolicyBundle b = liveBundle();
        KubernetesClusterConfig c = cluster("MANUAL");
        set(c, "policyServedApprovalMode", "MANUAL");
        set(c, "policyServedAt", NOW.minusSeconds(10));
        policy.serve(c, automation, b);
        verify(repo, never()).recordPolicyServed(anyLong(), any(), any());

        set(c, "policyServedAt", NOW.minusSeconds(31));
        policy.serve(c, automation, b);
        verify(repo).recordPolicyServed(1L, "MANUAL", NOW);
    }

    @Test
    void aClusterBackOnAutonomousClearsTheServedModeOnceThenStopsWriting() throws Exception {
        everythingReady();
        AutomationPolicyBundle b = liveBundle();
        KubernetesClusterConfig c = cluster("AUTONOMOUS");
        set(c, "policyServedApprovalMode", "MANUAL");
        set(c, "policyServedAt", NOW.minusSeconds(5));
        String served = policy.serve(c, automation, b);
        assertThat(JSON.readTree(served).has("approvalMode")).isFalse();
        verify(repo).recordPolicyServed(eq(1L), isNull(), eq(NOW));

        org.mockito.Mockito.reset(repo);
        set(c, "policyServedApprovalMode", null);
        policy.serve(c, automation, b);
        verifyNoInteractions(repo);
    }

    @Test
    void aBookkeepingFailureNeverFailsThePolicyServe() throws Exception {
        everythingReady();
        org.mockito.Mockito.doThrow(new IllegalStateException("db down")).when(repo).recordPolicyServed(anyLong(), any(), any());
        assertThat(policy.serve(cluster("MANUAL"), automation, liveBundle())).contains("\"approvalMode\":\"manual\"");
    }

    private static void set(KubernetesClusterConfig c, String field, Object value) throws Exception {
        var f = KubernetesClusterConfig.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(c, value);
    }

    // ------------------------------------------------------------------ heartbeat features (3.8)

    private ManualBookkeeping bookkeeping() {
        return new ManualBookkeeping(repo, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void aStandbyHeartbeatWritesNothingAndALeaderHeartbeatSetsTheFeatures() throws Exception {
        KubernetesClusterConfig c = cluster("MANUAL");
        Map<String, Object> standby = Map.of("leader", false, "features", List.of("manual-v1"), "drainTimeoutSeconds", 1200);
        bookkeeping().recordHeartbeat(c, automation, standby);
        bookkeeping().recordHeartbeat(c, automation, Map.of("features", List.of("manual-v1"))); // older agent: no leader field
        verifyNoInteractions(repo);

        bookkeeping().recordHeartbeat(c, automation,
                Map.of("leader", true, "features", List.of("manual-v1"), "drainTimeoutSeconds", 3600));
        verify(repo).recordAgentFeatures(1L, "manual-v1", NOW, 3600);
    }

    @Test
    void aLeaderHeartbeatWithoutValidFeaturesClearsThemAndKeepsTheHeartbeatSafe() throws Exception {
        KubernetesClusterConfig c = cluster("MANUAL");
        set(c, "automationAgentFeatures", "manual-v1");
        set(c, "automationAgentFeaturesSeenAt", NOW.minusSeconds(60));
        bookkeeping().recordHeartbeat(c, automation, Map.of("leader", true));
        verify(repo).recordAgentFeatures(1L, null, NOW, null);

        org.mockito.Mockito.reset(repo);
        for (Object bad : new Object[] {List.of("Bad Token"), List.of("x".repeat(40)), "manual-v1", List.of(1),
                java.util.Collections.nCopies(17, "a")}) {
            bookkeeping().recordHeartbeat(c, automation, Map.of("leader", true, "features", bad));
        }
        verify(repo, org.mockito.Mockito.times(5)).recordAgentFeatures(1L, null, NOW, null);
    }

    @Test
    void featureWritesAreThrottledToFiveMinutesUnlessTheListChanged() throws Exception {
        KubernetesClusterConfig c = cluster("MANUAL");
        set(c, "automationAgentFeatures", "manual-v1");
        set(c, "automationAgentDrainTimeoutSeconds", 1200);
        set(c, "automationAgentFeaturesSeenAt", NOW.minusSeconds(120));
        Map<String, Object> same = Map.of("leader", true, "features", List.of("manual-v1"), "drainTimeoutSeconds", 1200);
        bookkeeping().recordHeartbeat(c, automation, same);
        verify(repo, never()).recordAgentFeatures(anyLong(), any(), any(), any());

        set(c, "automationAgentFeaturesSeenAt", NOW.minusSeconds(301));
        bookkeeping().recordHeartbeat(c, automation, same);
        verify(repo).recordAgentFeatures(1L, "manual-v1", NOW, 1200);
    }

    @Test
    void theVersionSuffixFallbackAndNonAutomationPrincipalsNeverWriteFeatures() throws Exception {
        KubernetesClusterConfig c = cluster("MANUAL");
        Map<String, Object> body = Map.of("leader", true, "features", List.of("manual-v1"), "agentVersion", "1.0.0-autoscaler");
        bookkeeping().recordHeartbeat(c, AgentPrincipal.cluster("111122223333", "prod"), body); // DATA role
        bookkeeping().recordHeartbeat(c, AgentPrincipal.account("111122223333"), body);
        bookkeeping().recordHeartbeat(c, AgentPrincipal.legacyGlobal(), body);
        bookkeeping().recordHeartbeat(c, null, body);
        verifyNoInteractions(repo);
    }

    @Test
    void drainTimeoutOutOfRangeIsIgnored() {
        assertThat(ManualBookkeeping.validDrainTimeout(59)).isNull();
        assertThat(ManualBookkeeping.validDrainTimeout(60)).isEqualTo(60);
        assertThat(ManualBookkeeping.validDrainTimeout(86_400)).isEqualTo(86_400);
        assertThat(ManualBookkeeping.validDrainTimeout(86_401)).isNull();
        assertThat(ManualBookkeeping.validDrainTimeout("1200")).isNull();
        assertThat(ManualBookkeeping.validDrainTimeout(12.5)).isNull();
    }
}
