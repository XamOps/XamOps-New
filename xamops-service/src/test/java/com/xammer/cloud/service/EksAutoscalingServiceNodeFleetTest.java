package com.xammer.cloud.service;

import com.xammer.cloud.config.K8sAutomationReadinessProperties;
import com.xammer.cloud.domain.AgentDataCache;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.repository.AgentDataCacheRepository;
import com.xammer.cloud.repository.AgentEventRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.EksDashboardCacheRepository;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.security.K8sAutomationAccessService;
import com.xammer.cloud.service.spot.ServerTime;
import com.xammer.cloud.service.spot.SpotInterruptionProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code /node-fleet} serves only what a producer observed (design §B5.1, AC14, AC20): the
 * autoscaler's report while fresh (lists normalized, {@code interruptionNoticesReported} decided
 * before normalization), otherwise synthesized/empty fleets with {@code null} for every list nobody
 * reported, the not-reported flags, and UTC {@code Z} timestamps. The JVM default zone is
 * {@code Asia/Kolkata} throughout, as in production.
 */
class EksAutoscalingServiceNodeFleetTest {

    private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");
    private static final String REPORT = EksAutoscalingService.DT_NODE_OPTIMIZER_REPORT;
    private static final String LEGACY = EksAutoscalingService.DT_NODE_FLEET;
    private static final String SNAPSHOT = "k8s-resources";

    private final Map<String, AgentDataCache> cache = new HashMap<>();
    private EksAutoscalingService service;
    private KubernetesClusterConfig config;
    private TimeZone previousZone;

    @BeforeEach
    void setUp() {
        previousZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
        AgentDataCacheRepository cacheRepository = mock(AgentDataCacheRepository.class);
        when(cacheRepository.findByClusterConfigIdAndDataType(anyLong(), anyString()))
                .thenAnswer(inv -> Optional.ofNullable(cache.get(inv.getArgument(0) + "|" + inv.getArgument(1))));
        service = new EksAutoscalingService(mock(KubernetesClusterConfigRepository.class), cacheRepository,
                mock(AgentEventRepository.class), mock(EksDashboardCacheRepository.class),
                mock(CloudAccountRepository.class), new K8sAutomationPolicyNormalizer(new K8sAutomationReadinessProperties()),
                mock(K8sAutomationAccessService.class), SpotInterruptionProperties.defaults(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        config = new KubernetesClusterConfig();
        config.setId(100L);
        config.setClusterName("prod-eks");
    }

    @AfterEach
    void restoreZone() {
        TimeZone.setDefault(previousZone);
    }

    /** A row received {@code age} ago, written like the server writes it (LocalDateTime in the JVM zone). */
    private void store(String dataType, String json, Duration age) {
        AgentDataCache row = new AgentDataCache(config.getId(), dataType, json, 1);
        row.setReceivedAt(ServerTime.serverLocal(NOW.minus(age)));
        cache.put(config.getId() + "|" + dataType, row);
    }

    private static final String SNAPSHOT_NO_USAGE = "{\"nodes\":["
            + "{\"name\":\"a\",\"capacityType\":\"spot\"},"
            + "{\"name\":\"b\",\"capacityType\":\"on-demand\"},"
            + "{\"name\":\"c\",\"capacityType\":\"on-demand\"}]}";

    private static void assertUnobservedLists(Map<String, Object> fleet) {
        for (String key : List.of("interruptedNodes", "interruptionNotices", "spotCandidates", "estimatedSavingsPct")) {
            assertThat(fleet).as(key).containsKey(key);
            assertThat(fleet.get(key)).as(key).isNull();
        }
        assertThat(fleet).containsEntry("interruptedNodesReported", false)
                .containsEntry("interruptionNoticesReported", false);
    }

    // ── Branch 1: fresh node-optimizer-report ────────────────────────────

    @Test
    void aFreshReportWithNullAndMissingListsServesFourEmptyLists_AC14() {
        store(REPORT, "{\"totalNodes\":3,\"spotNodes\":1,\"idleNodes\":null,\"spotCandidates\":null,"
                + "\"estimatedSavingsPct\":null,\"collectedAt\":\"2026-10-24T11:59:00Z\"}", Duration.ofMinutes(1));

        Map<String, Object> fleet = service.getNodeFleetReport(config);

        for (String key : List.of("interruptedNodes", "interruptionNotices", "idleNodes", "spotCandidates")) {
            assertThat(fleet.get(key)).as(key).isEqualTo(List.of());
        }
        assertThat(fleet).containsEntry("interruptionNoticesReported", false)
                .containsEntry("interruptedNodesReported", true)
                .containsEntry("collectedAt", "2026-10-24T11:59:00Z")
                .containsEntry("totalNodes", 3)
                .doesNotContainKey("interruptedNodesReason");
    }

    @Test
    void anEmptyInterruptionNoticesListIsReported() {
        store(REPORT, "{\"interruptedNodes\":[],\"interruptionNotices\":[],\"idleNodes\":[],\"spotCandidates\":[]}",
                Duration.ofMinutes(2));

        assertThat(service.getNodeFleetReport(config)).containsEntry("interruptionNoticesReported", true)
                .containsEntry("interruptedNodesReported", true);
    }

    @Test
    void aNullInterruptionNoticesListIsNotReported() {
        store(REPORT, "{\"interruptedNodes\":[\"n1\"],\"interruptionNotices\":null}", Duration.ofMinutes(2));

        Map<String, Object> fleet = service.getNodeFleetReport(config);

        assertThat(fleet).containsEntry("interruptionNoticesReported", false);
        assertThat(fleet.get("interruptionNotices")).isEqualTo(List.of());
        assertThat(fleet.get("interruptedNodes")).isEqualTo(List.of("n1"));
    }

    @Test
    void detectionFailedAndNoticesPassThrough() {
        store(REPORT, "{\"interruptedNodes\":[],\"interruptedNodesDetectionFailed\":true,"
                + "\"interruptionNotices\":[{\"nodeName\":\"n2\",\"kind\":\"interruption\"}]}", Duration.ofSeconds(30));

        Map<String, Object> fleet = service.getNodeFleetReport(config);

        assertThat(fleet).containsEntry("interruptedNodesDetectionFailed", true)
                .containsEntry("interruptionNoticesReported", true);
        assertThat((List<?>) fleet.get("interruptionNotices")).hasSize(1);
    }

    @Test
    void normalizeReportDecidesReportedBeforeNormalizing() {
        Map<String, Object> missing = new LinkedHashMap<>();
        EksAutoscalingService.normalizeReport(missing);
        assertThat(missing).containsEntry("interruptionNoticesReported", false)
                .containsEntry("interruptionNotices", List.of());

        Map<String, Object> nulled = new LinkedHashMap<>();
        nulled.put("interruptionNotices", null);
        EksAutoscalingService.normalizeReport(nulled);
        assertThat(nulled).containsEntry("interruptionNoticesReported", false)
                .containsEntry("interruptionNotices", List.of());

        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("interruptionNotices", List.of());
        EksAutoscalingService.normalizeReport(empty);
        assertThat(empty).containsEntry("interruptionNoticesReported", true);
    }

    // ── Branch 2: legacy node-fleet-report ───────────────────────────────

    @Test
    void aFreshLegacyFleetReportIsNormalizedTheSameWay() {
        store(REPORT, "{\"interruptionNotices\":[]}", Duration.ofMinutes(30));
        store(LEGACY, "{\"totalNodes\":2,\"idleNodes\":null}", Duration.ofMinutes(1));

        Map<String, Object> fleet = service.getNodeFleetReport(config);

        assertThat(fleet).containsEntry("totalNodes", 2)
                .containsEntry("interruptedNodesReported", true)
                .containsEntry("interruptionNoticesReported", false);
        assertThat(fleet.get("idleNodes")).isEqualTo(List.of());
    }

    // ── Branch 3: synthesized from k8s-resources ─────────────────────────

    @Test
    void aFifteenMinuteOldReportFallsThroughAsStale_AC14() {
        store(REPORT, "{\"interruptedNodes\":[],\"interruptionNotices\":[]}", Duration.ofMinutes(15));
        store(SNAPSHOT, SNAPSHOT_NO_USAGE, Duration.ofMinutes(3));

        Map<String, Object> fleet = service.getNodeFleetReport(config);

        assertUnobservedLists(fleet);
        assertThat(fleet).containsEntry("interruptedNodesReason", "stale")
                .containsEntry("interruptedNodesReportAt", "2026-10-24T11:45:00Z")
                .containsEntry("totalNodes", 3)
                .containsEntry("spotNodes", 1)
                .containsEntry("onDemandNodes", 2);
    }

    @Test
    void anElevenMinuteOldReportIsStaleUnderTheIstDefaultZoneWithAUtcClock_AC20() {
        // receivedAt is IST wall time (LocalDateTime.now() on the server), 11 minutes before the UTC clock.
        AgentDataCache row = new AgentDataCache(config.getId(), REPORT, "{\"interruptionNotices\":[]}", 1);
        row.setReceivedAt(LocalDateTime.ofInstant(NOW.minus(Duration.ofMinutes(11)), ZoneId.of("Asia/Kolkata")));
        cache.put(config.getId() + "|" + REPORT, row);
        store(SNAPSHOT, SNAPSHOT_NO_USAGE, Duration.ofMinutes(1));

        Map<String, Object> fleet = service.getNodeFleetReport(config);

        assertThat(fleet).containsEntry("interruptedNodesReported", false)
                .containsEntry("interruptedNodesReason", "stale")
                .containsEntry("interruptedNodesReportAt", "2026-10-24T11:49:00Z")
                .containsEntry("collectedAt", "2026-10-24T11:59:00Z");
    }

    @Test
    void aSnapshotWithoutUsageServesNullIdleNodesAndNotReported() {
        store(SNAPSHOT, SNAPSHOT_NO_USAGE, Duration.ofMinutes(4));

        Map<String, Object> fleet = service.getNodeFleetReport(config);

        assertUnobservedLists(fleet);
        assertThat(fleet).containsKey("idleNodes");
        assertThat(fleet.get("idleNodes")).isNull();
        assertThat(fleet).containsEntry("interruptedNodesReason", "not-reported")
                .doesNotContainKey("interruptedNodesReportAt");
        assertThat((String) fleet.get("collectedAt")).isEqualTo("2026-10-24T11:56:00Z").endsWith("Z");
    }

    @Test
    void aSnapshotNodeWithCpuUsageGivesARealIdleList() {
        store(SNAPSHOT, "{\"nodes\":[{\"name\":\"a\",\"capacityType\":\"spot\",\"cpuUsagePercent\":4},"
                + "{\"name\":\"b\",\"capacityType\":\"on-demand\",\"cpuUsagePercent\":55}]}", Duration.ofMinutes(1));

        Map<String, Object> fleet = service.getNodeFleetReport(config);

        assertThat((List<?>) fleet.get("idleNodes")).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> idle = ((List<Map<String, Object>>) fleet.get("idleNodes")).get(0);
        assertThat(idle).containsEntry("nodeName", "a").containsEntry("cpuUsagePct", 4.0);
        assertUnobservedLists(fleet);
    }

    // ── Branch 4: empty fleet ────────────────────────────────────────────

    @Test
    void noDataServesAnEmptyFleetWithNullLists() {
        Map<String, Object> fleet = service.getNodeFleetReport(config);

        assertUnobservedLists(fleet);
        assertThat(fleet.get("idleNodes")).isNull();
        assertThat(fleet).containsKey("collectedAt");
        assertThat(fleet.get("collectedAt")).isNull();
        assertThat(fleet).containsEntry("totalNodes", 0).containsEntry("interruptedNodesReason", "not-reported");
    }

    @Test
    void aStaleReportWithoutASnapshotServesAnEmptyFleetMarkedStale() {
        store(REPORT, "{\"interruptionNotices\":[]}", Duration.ofMinutes(20));

        Map<String, Object> fleet = service.getNodeFleetReport(config);

        assertUnobservedLists(fleet);
        assertThat(fleet).containsEntry("interruptedNodesReason", "stale")
                .containsEntry("interruptedNodesReportAt", "2026-10-24T11:40:00Z");
        assertThat(fleet.get("collectedAt")).isNull();
    }

    @Test
    void aNullConfigServesTheEmptyFleet() {
        Map<String, Object> fleet = service.getNodeFleetReport(null);

        assertUnobservedLists(fleet);
        assertThat(fleet).containsEntry("interruptedNodesReason", "not-reported");
    }
}
