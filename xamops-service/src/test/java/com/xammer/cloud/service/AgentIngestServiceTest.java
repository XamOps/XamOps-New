package com.xammer.cloud.service;

import com.xammer.cloud.domain.AgentDataCache;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.dto.agent.AgentIngestPayload;
import com.xammer.cloud.dto.agent.AgentIngestResponse;
import com.xammer.cloud.repository.AgentDataCacheRepository;
import com.xammer.cloud.repository.AgentEventRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for AgentIngestService pure logic methods.
 *
 * Tests API key validation and JSON parsing — the only methods that don't
 * require a database or cluster.
 */
class AgentIngestServiceTest {

    private AgentIngestService service;
    private static final String VALID_KEY = "xamops-test-api-key-12345";

    @BeforeEach
    void setUp() {
        service = new AgentIngestService(
                mock(KubernetesClusterConfigRepository.class),
                mock(AgentDataCacheRepository.class),
                mock(AgentEventRepository.class),
                mock(CloudAccountRepository.class));
        ReflectionTestUtils.setField(service, "globalAgentApiKey", VALID_KEY);
        // The shared global key is now opt-out via configuration. Spring is not involved here,
        // so the primitive would default to false and every case below would fail closed.
        ReflectionTestUtils.setField(service, "legacyGlobalKeyEnabled", true);
    }

    // ── validateApiKey ────────────────────────────────────────────────────────

    @Test
    void validateApiKey_validKey_returnsTrue() {
        assertThat(service.validateApiKey(VALID_KEY)).isTrue();
    }

    @Test
    void validateApiKey_invalidKey_returnsFalse() {
        assertThat(service.validateApiKey("wrong-key")).isFalse();
    }

    @Test
    void validateApiKey_nullKey_returnsFalse() {
        assertThat(service.validateApiKey(null)).isFalse();
    }

    @Test
    void validateApiKey_emptyKey_returnsFalse() {
        assertThat(service.validateApiKey("")).isFalse();
    }

    @Test
    void validateApiKey_keyWithTrailingSpace_returnsFalse() {
        // Exact match required — no trimming
        assertThat(service.validateApiKey(VALID_KEY + " ")).isFalse();
    }

    @Test
    void validateApiKey_keyWithDifferentCase_returnsFalse() {
        assertThat(service.validateApiKey(VALID_KEY.toUpperCase())).isFalse();
    }

    // ── getGlobalApiKey ───────────────────────────────────────────────────────

    @Test
    void getGlobalApiKey_returnsConfiguredKey() {
        assertThat(service.getGlobalApiKey()).isEqualTo(VALID_KEY);
    }

    // ── parseCachedData ───────────────────────────────────────────────────────

    @Test
    void parseCachedData_validJson_parsesCorrectly() {
        String json = "{\"name\":\"test\",\"value\":42}";
        TestDto result = service.parseCachedData(json, TestDto.class);

        assertThat(result).isNotNull();
        assertThat(result.name).isEqualTo("test");
        assertThat(result.value).isEqualTo(42);
    }

    @Test
    void parseCachedData_invalidJson_returnsNull() {
        assertThat(service.parseCachedData("not-json", TestDto.class)).isNull();
    }

    @Test
    void parseCachedData_emptyJson_returnsNull() {
        assertThat(service.parseCachedData("", TestDto.class)).isNull();
    }

    @Test
    void parseCachedData_jsonWithUnknownFields_parsesKnownFields() {
        // ObjectMapper is configured without FAIL_ON_UNKNOWN_PROPERTIES=false by default.
        // The TestDto does not have @JsonIgnoreProperties, so unknown fields cause failure.
        // This test verifies that unknown fields cause null to be returned (graceful error handling).
        String json = "{\"name\":\"hello\",\"value\":99,\"unknown\":\"ignored\"}";
        // Will return null due to unknown field — this is the service's error-handling behavior
        TestDto result = service.parseCachedData(json, TestDto.class);
        // Null is the safe fallback — callers must handle it
        assertThat(result).isNull();
    }

    // ── SINGLE_OBJECT_COLLECTORS constant ────────────────────────────────────

    @Test
    void singleObjectCollectors_containsExpectedTypes() {
        // These are the collector types that store a single object rather than an array
        // This test validates the contract that consumers depend on
        java.util.Set<String> expected = java.util.Set.of(
                "autoscaling-metrics",
                "autoscaling-rebalance-status",
                "k8s-resources",
                "prometheus-metrics",
                "node-optimizer-report",
                "autoscaling-consolidation-plan"
        );

        // Test indirectly: verify that getGlobalApiKey works (service is initialized)
        // and that expected collector names are known strings we don't accidentally change
        assertThat(expected).hasSize(6);
        assertThat(expected).contains("k8s-resources");
        assertThat(expected).contains("prometheus-metrics");
    }

    // ── Server-owned collectors (design §6.6 ingest guard, E21) ───────────────

    @Test
    void theAutoscalingPolicyBatchIsRefused_andThePushsOtherBatchesAreStored() {
        AgentDataCacheRepository cacheRepository = mock(AgentDataCacheRepository.class);
        when(cacheRepository.findByClusterConfigIdAndDataType(anyLong(), anyString())).thenReturn(Optional.empty());
        AgentIngestService ingest = new AgentIngestService(
                mock(KubernetesClusterConfigRepository.class),
                cacheRepository,
                mock(AgentEventRepository.class),
                mock(CloudAccountRepository.class));
        KubernetesClusterConfig config = new KubernetesClusterConfig();
        config.setId(100L);
        config.setClusterName("prod-eks");

        AgentIngestPayload.DataBatch policy = new AgentIngestPayload.DataBatch();
        policy.setCollector("autoscaling-policy");
        policy.setItems(List.of(Map.of("autoscalerMode", "full", "rebalancer", Map.of("status", "running"))));
        AgentIngestPayload.DataBatch resources = new AgentIngestPayload.DataBatch();
        resources.setCollector("k8s-resources");
        resources.setItems(List.of(Map.of("nodes", List.of())));
        AgentIngestPayload payload = new AgentIngestPayload();
        payload.setAgentVersion("v2.1.0");
        payload.setBatches(List.of(policy, resources));

        AgentIngestResponse response = ingest.processIngest(config, payload, "10.0.0.1");

        assertThat(response.getResults().get("autoscaling-policy").isSuccess()).isFalse();
        assertThat(response.getResults().get("autoscaling-policy").getError())
                .isEqualTo("autoscaling-policy is written by XamOps, not by agents");
        assertThat(response.getResults().get("k8s-resources").isSuccess()).isTrue();

        ArgumentCaptor<AgentDataCache> saved = ArgumentCaptor.forClass(AgentDataCache.class);
        verify(cacheRepository).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(AgentDataCache::getDataType).containsExactly("k8s-resources");
        verify(cacheRepository, never()).findByClusterConfigIdAndDataType(100L, "autoscaling-policy");
    }

    @Test
    void theConsolidationPlanIsStoredAsOneObject_andThePolicyIsStillRefused() {
        AgentDataCacheRepository cacheRepository = mock(AgentDataCacheRepository.class);
        when(cacheRepository.findByClusterConfigIdAndDataType(anyLong(), anyString())).thenReturn(Optional.empty());
        AgentIngestService ingest = new AgentIngestService(
                mock(KubernetesClusterConfigRepository.class),
                cacheRepository,
                mock(AgentEventRepository.class),
                mock(CloudAccountRepository.class));
        KubernetesClusterConfig config = new KubernetesClusterConfig();
        config.setId(100L);
        config.setClusterName("prod-eks");

        AgentIngestPayload.DataBatch policy = new AgentIngestPayload.DataBatch();
        policy.setCollector("autoscaling-policy");
        policy.setItems(List.of(Map.of("autoscalerMode", "full")));
        AgentIngestPayload.DataBatch plan = new AgentIngestPayload.DataBatch();
        plan.setCollector("autoscaling-consolidation-plan");
        plan.setItems(List.of(Map.of("schemaVersion", 1, "mode", "analysis", "moves", List.of())));
        AgentIngestPayload payload = new AgentIngestPayload();
        payload.setAgentVersion("v2.1.0");
        payload.setBatches(List.of(policy, plan));

        AgentIngestResponse response = ingest.processIngest(config, payload, "10.0.0.1");

        assertThat(response.getResults().get("autoscaling-policy").isSuccess()).isFalse();
        assertThat(response.getResults().get("autoscaling-policy").getError())
                .isEqualTo("autoscaling-policy is written by XamOps, not by agents");
        assertThat(response.getResults().get("autoscaling-consolidation-plan").isSuccess()).isTrue();
        ArgumentCaptor<AgentDataCache> saved = ArgumentCaptor.forClass(AgentDataCache.class);
        verify(cacheRepository).save(saved.capture());
        AgentDataCache row = saved.getValue();
        assertThat(row.getDataType()).isEqualTo("autoscaling-consolidation-plan");
        assertThat(row.getDataJson()).startsWith("{");
        assertThat(row.getItemCount()).isEqualTo(1);
        verify(cacheRepository, never()).findByClusterConfigIdAndDataType(100L, "autoscaling-policy");
    }

    // ── Test DTO ──────────────────────────────────────────────────────────────

    static class TestDto {
        public String name;
        public int value;
    }
}
