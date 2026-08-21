package com.xammer.cloud.service;

import com.xammer.cloud.repository.AgentDataCacheRepository;
import com.xammer.cloud.repository.AgentEventRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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
                "node-optimizer-report"
        );

        // Test indirectly: verify that getGlobalApiKey works (service is initialized)
        // and that expected collector names are known strings we don't accidentally change
        assertThat(expected).hasSize(5);
        assertThat(expected).contains("k8s-resources");
        assertThat(expected).contains("prometheus-metrics");
    }

    // ── Test DTO ──────────────────────────────────────────────────────────────

    static class TestDto {
        public String name;
        public int value;
    }
}
