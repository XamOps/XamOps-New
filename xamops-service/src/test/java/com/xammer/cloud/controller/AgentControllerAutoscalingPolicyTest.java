package com.xammer.cloud.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.config.K8sAutomationReadinessProperties;
import com.xammer.cloud.domain.AgentDataCache;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.dto.agent.AgentPrincipal;
import com.xammer.cloud.repository.AgentDataCacheRepository;
import com.xammer.cloud.repository.AgentEventRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.ExplainRequestRepository;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.repository.UserTabAccessRepository;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.security.K8sAutomationAccessService;
import com.xammer.cloud.service.AgentApiKeyService;
import com.xammer.cloud.service.AgentIngestService;
import com.xammer.cloud.service.DbAgentService;
import com.xammer.cloud.service.EksAutoscalingService;
import com.xammer.cloud.service.K8sAutomationPolicyNormalizer;
import com.xammer.cloud.service.OpenObserveService;
import com.xammer.cloud.service.RedisCacheService;
import com.xammer.cloud.service.sre.SreInvestigationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code GET /api/v1/agent/autoscaling-policy} serves a complete, normalized bundle (design §6.6,
 * E5, NIT 11): never the raw stored JSON, never the old {@code {"autoscalerMode":"full"}} fallback.
 */
class AgentControllerAutoscalingPolicyTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KEY = "cluster-scoped-key";
    private static final long AWS = 111111111111L;

    private AgentDataCacheRepository cacheRepository;
    private CloudAccountRepository cloudAccountRepository;
    private KubernetesClusterConfigRepository clusterConfigRepository;
    private AgentController controller;
    private KubernetesClusterConfig config;
    private CloudAccount owner;

    @BeforeEach
    void setUp() {
        AgentIngestService ingest = mock(AgentIngestService.class);
        when(ingest.authenticate(eq(KEY), isNull())).thenReturn(AgentPrincipal.cluster("111111111111", "prod-eks"));
        when(ingest.checkOwnership(any(), anyString(), anyString())).thenReturn(null);
        cacheRepository = mock(AgentDataCacheRepository.class);
        cloudAccountRepository = mock(CloudAccountRepository.class);
        clusterConfigRepository = mock(KubernetesClusterConfigRepository.class);
        when(cloudAccountRepository.findById(anyLong())).thenReturn(Optional.empty());
        when(cloudAccountRepository.findByAwsAccountId(anyString())).thenReturn(List.of());
        when(cacheRepository.findByClusterConfigIdAndDataType(anyLong(), anyString())).thenReturn(Optional.empty());

        K8sAutomationAccessService accessService = new K8sAutomationAccessService(mock(CloudAccountAccessService.class),
                cloudAccountRepository, clusterConfigRepository, mock(UserRepository.class),
                mock(UserTabAccessRepository.class));
        controller = new AgentController(ingest, mock(DbAgentService.class), clusterConfigRepository, cacheRepository,
                mock(AgentEventRepository.class), mock(RedisCacheService.class), cloudAccountRepository,
                mock(SreInvestigationService.class), mock(OpenObserveService.class),
                mock(ExplainRequestRepository.class), mock(AgentApiKeyService.class),
                new K8sAutomationPolicyNormalizer(new K8sAutomationReadinessProperties()), accessService);

        owner = new CloudAccount();
        owner.setId(7L);
        owner.setAwsAccountId("111111111111");
        owner.setProvider("AWS");
        owner.setAccessType("read-write");
        owner.setStatus("CONNECTED");
        when(cloudAccountRepository.findById(7L)).thenReturn(Optional.of(owner));

        config = new KubernetesClusterConfig();
        config.setId(100L);
        config.setCloudAccountId(7L);
        config.setClusterName("prod-eks");
        config.setAutoscalingEnabled(true);
        when(clusterConfigRepository.findByAgentApiKey(KEY)).thenReturn(Optional.of(config));
    }

    private void storePolicy(String json) {
        AgentDataCache row = new AgentDataCache(100L, EksAutoscalingService.DT_POLICY, json, 1);
        when(cacheRepository.findByClusterConfigIdAndDataType(100L, EksAutoscalingService.DT_POLICY))
                .thenReturn(Optional.of(row));
    }

    private JsonNode served() throws Exception {
        ResponseEntity<?> r = controller.getAutoscalingPolicy("Bearer " + KEY, "prod-eks", AWS);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody()).isInstanceOf(String.class);
        return JSON.readTree((String) r.getBody());
    }

    private static void assertComplete(JsonNode bundle) {
        for (String section : List.of("autoscalerMode", "scaleOutPolicy", "evictorPolicy", "workloadRightsizerPolicy",
                "hpaManagerPolicy", "vpaManagerPolicy", "nodeOptimizerPolicy", "nodeTemplates", "rebalancer",
                "safetyGuards")) {
            assertThat(bundle.hasNonNull(section)).as(section).isTrue();
        }
        assertThat(bundle.get("safetyGuards").get("respectPodDisruptionBudgets").asBoolean()).isTrue();
        assertThat(bundle.get("nodeOptimizerPolicy").get("maxParallelDrains").asInt()).isEqualTo(1);
    }

    @Test
    void noStoredPolicyIsTheCompleteDefault_notThePartialFallback() throws Exception {
        JsonNode bundle = served();
        assertComplete(bundle);
        assertThat(bundle.get("autoscalerMode").asText()).isEqualTo("full");
        assertThat(bundle.get("evictorPolicy").get("analyseOnly").asBoolean()).isTrue();
        assertThat(bundle.size()).isGreaterThan(1);
    }

    @Test
    void aStoredRunningRebalancerIsServedIdle() throws Exception {
        storePolicy("{\"autoscalerMode\":\"full\",\"rebalancer\":{\"status\":\"running\",\"enabled\":true}}");
        JsonNode bundle = served();
        assertComplete(bundle);
        assertThat(bundle.get("rebalancer").get("status").asText()).isEqualTo("idle");
        assertThat(bundle.get("rebalancer").get("enabled").asBoolean()).isFalse();
    }

    @Test
    void aReadWriteConnectedOwnerGetsTheStoredEffectivePolicy() throws Exception {
        storePolicy("{\"autoscalerMode\":\"scoped\",\"evictorPolicy\":{\"analyseOnly\":false,\"targetUtilizationCpu\":66},"
                + "\"workloadRightsizerPolicy\":{\"enabled\":true,\"applyMode\":\"immediate\"}}");
        JsonNode bundle = served();
        assertThat(bundle.get("autoscalerMode").asText()).isEqualTo("scoped");
        assertThat(bundle.get("evictorPolicy").get("targetUtilizationCpu").asInt()).isEqualTo(66);
        assertThat(bundle.get("evictorPolicy").get("analyseOnly").asBoolean()).as("evictor-live not ready").isTrue();
        assertThat(bundle.get("workloadRightsizerPolicy").get("enabled").asBoolean()).as("retired").isFalse();
    }

    @Test
    void aReadOnlyOwnerIsServedDisabled() throws Exception {
        storePolicy("{\"autoscalerMode\":\"full\"}");
        owner.setAccessType("read-only");
        JsonNode bundle = served();
        assertComplete(bundle);
        assertThat(bundle.get("autoscalerMode").asText()).isEqualTo("disabled");
    }

    @Test
    void anUnverifiedOwnerIsServedDisabled() throws Exception {
        storePolicy("{\"autoscalerMode\":\"full\"}");
        owner.setStatus("FAILED");
        assertThat(served().get("autoscalerMode").asText()).isEqualTo("disabled");
    }

    @Test
    void anOwnerThatCantBeFoundIsServedDisabled() throws Exception {
        storePolicy("{\"autoscalerMode\":\"full\"}");
        config.setCloudAccountId(999L);
        assertThat(served().get("autoscalerMode").asText()).isEqualTo("disabled");
    }

    @Test
    void aLegacyConfigFindsItsOwnerByThePaddedAwsNumber() throws Exception {
        storePolicy("{\"autoscalerMode\":\"scoped\"}");
        config.setCloudAccountId(AWS);
        when(cloudAccountRepository.findByAwsAccountId("111111111111")).thenReturn(List.of(owner));
        assertThat(served().get("autoscalerMode").asText()).isEqualTo("scoped");
    }

    @Test
    void autoscalingNotEnabledIsStill404_andABadKeyStill401() {
        config.setAutoscalingEnabled(false);
        assertThat(controller.getAutoscalingPolicy("Bearer " + KEY, "prod-eks", AWS).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.getAutoscalingPolicy("Bearer wrong", "prod-eks", AWS).getStatusCode().value()).isEqualTo(401);
    }
}
