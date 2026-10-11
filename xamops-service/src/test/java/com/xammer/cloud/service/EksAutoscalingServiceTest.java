package com.xammer.cloud.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.config.K8sAutomationReadinessProperties;
import com.xammer.cloud.domain.AgentDataCache;
import com.xammer.cloud.domain.AgentEvent;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.dto.autoscaling.AutoscalerDashboardDto;
import com.xammer.cloud.dto.autoscaling.AutoscalerDashboardDto.EvictorPolicyDto;
import com.xammer.cloud.dto.autoscaling.AutoscalerDashboardDto.NodeTemplateDto;
import com.xammer.cloud.dto.autoscaling.AutoscalerDashboardDto.RebalancerConfigDto;
import com.xammer.cloud.dto.autoscaling.AutoscalerDashboardDto.SafetyGuardsDto;
import com.xammer.cloud.dto.autoscaling.AutoscalerDashboardDto.ScaleOutPolicyDto;
import com.xammer.cloud.dto.autoscaling.NodeOptimizerPolicyDto;
import com.xammer.cloud.exception.K8sAutomationReason;
import com.xammer.cloud.exception.K8sAutomationRefusedException;
import com.xammer.cloud.repository.AgentDataCacheRepository;
import com.xammer.cloud.repository.AgentEventRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.EksDashboardCacheRepository;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.security.K8sAutomationAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EksAutoscalingService on the request-context path (design §6.6, E19, E20, NIT 7, orchestrator
 * decision 9). The agent_data_cache table is an in-memory map, so saves can be read back.
 */
class EksAutoscalingServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private AgentDataCacheRepository cacheRepository;
    private AgentEventRepository eventRepository;
    private KubernetesClusterConfigRepository configRepository;
    private K8sAutomationAccessService accessService;
    private K8sAutomationReadinessProperties readiness;
    private EksAutoscalingService service;
    private KubernetesClusterConfig config;
    private final Map<String, AgentDataCache> cache = new HashMap<>();
    private final List<AgentEvent> loggedEvents = new ArrayList<>();
    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    @BeforeEach
    void setUp() {
        cacheRepository = mock(AgentDataCacheRepository.class);
        eventRepository = mock(AgentEventRepository.class);
        configRepository = mock(KubernetesClusterConfigRepository.class);
        accessService = mock(K8sAutomationAccessService.class);
        readiness = new K8sAutomationReadinessProperties();
        service = new EksAutoscalingService(configRepository, cacheRepository, eventRepository,
                mock(EksDashboardCacheRepository.class), mock(CloudAccountRepository.class),
                new K8sAutomationPolicyNormalizer(readiness), accessService);

        when(cacheRepository.findByClusterConfigIdAndDataType(anyLong(), anyString()))
                .thenAnswer(inv -> Optional.ofNullable(cache.get(inv.getArgument(0) + "|" + inv.getArgument(1))));
        when(cacheRepository.save(any(AgentDataCache.class))).thenAnswer(inv -> {
            AgentDataCache row = inv.getArgument(0);
            cache.put(row.getClusterConfigId() + "|" + row.getDataType(), row);
            return row;
        });
        when(eventRepository.save(any(AgentEvent.class))).thenAnswer(inv -> {
            loggedEvents.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(eventRepository.findTop20ByClusterConfigIdAndEventTypeStartingWithOrderByCreatedAtDesc(anyLong(), anyString()))
                .thenReturn(List.of());

        config = new KubernetesClusterConfig();
        config.setId(100L);
        config.setCloudAccountId(7L);
        config.setClusterName("prod-eks");
        config.setRegion("us-east-1");
        config.setAutoscalingEnabled(false);

        logger = (Logger) LoggerFactory.getLogger(EksAutoscalingService.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
    }

    private void storeRow(String dataType, String json) {
        AgentDataCache row = new AgentDataCache(config.getId(), dataType, json, 1);
        cache.put(config.getId() + "|" + dataType, row);
    }

    private JsonNode storedPolicy() throws Exception {
        AgentDataCache row = cache.get(config.getId() + "|" + EksAutoscalingService.DT_POLICY);
        assertThat(row).as("a policy row was written").isNotNull();
        return JSON.readTree(row.getDataJson());
    }

    private static void assertComplete(JsonNode bundle) {
        for (String section : List.of("autoscalerMode", "scaleOutPolicy", "evictorPolicy", "workloadRightsizerPolicy",
                "hpaManagerPolicy", "vpaManagerPolicy", "nodeOptimizerPolicy", "nodeTemplates", "rebalancer",
                "safetyGuards")) {
            assertThat(bundle.hasNonNull(section)).as(section).isTrue();
        }
        assertThat(bundle.get("safetyGuards").get("criticalNamespaces")).isNotEmpty();
        List<String> namespaces = new ArrayList<>();
        bundle.get("safetyGuards").get("criticalNamespaces").forEach(n -> namespaces.add(n.asText()));
        assertThat(namespaces).contains("kube-system", "xamops-system");
    }

    // ── getDashboard ──────────────────────────────────────────────────────

    @Test
    void disabledWithNoStoredBundle_returnsEverySectionAsDefaults() {
        AutoscalerDashboardDto dto = service.getDashboard(config);

        assertThat(dto.isAutoscalingEnabled()).isFalse();
        assertThat(dto.getPolicySource()).isEqualTo("default");
        assertThat(dto.getScaleOutPolicy()).isNotNull();
        assertThat(dto.getEvictorPolicy()).isNotNull();
        assertThat(dto.getEvictorPolicy().isAnalyseOnly()).isTrue();
        assertThat(dto.getWorkloadRightsizerPolicy().isEnabled()).isFalse();
        assertThat(dto.getHpaManagerPolicy()).isNotNull();
        assertThat(dto.getVpaManagerPolicy()).isNotNull();
        assertThat(dto.getNodeOptimizerPolicy()).isNotNull();
        assertThat(dto.getRebalancer().getStatus()).isEqualTo("idle");
        assertThat(dto.getSafetyGuards().getCriticalNamespaces()).contains("kube-system", "xamops-system");
        assertThat(dto.getAgentStatus().getMode()).isEqualTo("disabled");
        assertThat(dto.getRecentEvents()).isEmpty();
        assertThat(dto.getRecentChanges()).isEmpty();
        assertThat(dto.getAgentPolicyBlockedBy()).isNull();
    }

    @Test
    void disabledWithAStoredBundle_returnsTheStoredSections() {
        storeRow(EksAutoscalingService.DT_POLICY,
                "{\"autoscalerMode\":\"scoped\",\"evictorPolicy\":{\"targetUtilizationCpu\":66,\"analyseOnly\":true}}");

        AutoscalerDashboardDto dto = service.getDashboard(config);

        assertThat(dto.getPolicySource()).isEqualTo("stored");
        assertThat(dto.getEvictorPolicy().getTargetUtilizationCpu()).isEqualTo(66);
        assertThat(dto.getSafetyGuards()).isNotNull();
    }

    @Test
    void theAgentPolicyBlockIsReported() {
        when(accessService.agentPolicyBlock(config)).thenReturn(K8sAutomationReason.READ_ONLY_CONNECTION);
        assertThat(service.getDashboard(config).getAgentPolicyBlockedBy()).isEqualTo("READ_ONLY_CONNECTION");
    }

    @Test
    void recentChangesAreTheConfigChangesWithUtcInstants() {
        AgentEvent change = new AgentEvent(100L, "autoscaling_config_change");
        change.setId(88123L);
        change.setDetails("Evictor policy updated by jane.doe");
        LocalDateTime serverTime = LocalDateTime.of(2026, 10, 10, 8, 20, 5);
        change.setCreatedAt(serverTime);
        when(eventRepository.findTop20ByClusterConfigIdAndEventTypeStartingWithOrderByCreatedAtDesc(100L, "autoscaling_"))
                .thenReturn(List.of(change));

        AutoscalerDashboardDto dto = service.getDashboard(config);

        assertThat(dto.getRecentChanges()).singleElement().satisfies(c -> {
            assertThat(c.getId()).isEqualTo("88123");
            assertThat(c.getEventType()).isEqualTo("autoscaling_config_change");
            assertThat(c.getMessage()).isEqualTo("Evictor policy updated by jane.doe");
            assertThat(c.getTimestamp()).isEqualTo(serverTime.atZone(ZoneId.systemDefault()).toInstant().toString())
                    .endsWith("Z");
        });
        // The agent's own feed stays separate: no agent_events fallback into recentEvents.
        assertThat(dto.getRecentEvents()).isEmpty();
    }

    @Test
    void anExtraKeyInTheAgentEventsRowKeepsRecentEvents() {
        storeRow(EksAutoscalingService.DT_EVENTS, "[{\"id\":\"spot-convert-1791563637\",\"timestamp\":\"2026-10-10T08:12:44Z\","
                + "\"type\":\"node-replace\",\"severity\":\"info\",\"message\":\"converted\",\"newAgentField\":42}]");

        assertThat(service.getDashboard(config).getRecentEvents()).singleElement()
                .satisfies(e -> assertThat(e.getId()).isEqualTo("spot-convert-1791563637"));
    }

    // ── Enable / disable (NIT 7) ──────────────────────────────────────────

    @Test
    void theFirstEnablePersistsTheCompleteDefault_andBothDirectionsAreRecorded() throws Exception {
        service.setAutoscalingEnabled(config, true, "jane.doe");

        assertThat(config.getAutoscalingEnabled()).isTrue();
        verify(configRepository).save(config);
        JsonNode stored = storedPolicy();
        assertComplete(stored);
        assertThat(stored.get("autoscalerMode").asText()).isEqualTo("full");
        assertThat(stored.get("evictorPolicy").get("analyseOnly").asBoolean()).isTrue();
        assertThat(stored.get("workloadRightsizerPolicy").get("enabled").asBoolean()).isFalse();
        assertThat(stored.get("rebalancer").get("status").asText()).isEqualTo("idle");

        storeRow(EksAutoscalingService.DT_POLICY, "{\"autoscalerMode\":\"scoped\"}");
        service.setAutoscalingEnabled(config, false, "jane.doe");
        service.setAutoscalingEnabled(config, true, "jane.doe");
        assertThat(storedPolicy().get("autoscalerMode").asText()).as("a stored bundle isn't overwritten").isEqualTo("scoped");

        assertThat(loggedEvents).extracting(AgentEvent::getDetails).containsExactly(
                "Node automation turned on by jane.doe",
                "Node automation turned off by jane.doe",
                "Node automation turned on by jane.doe");
        assertThat(loggedEvents).extracting(AgentEvent::getEventType).containsOnly("autoscaling_config_change");
    }

    // ── Section saves (orchestrator decision 9) ───────────────────────────

    private void assertPartialSaveLeavesACompleteBundle(Consumer<EksAutoscalingService> save, String logged) throws Exception {
        cache.clear();
        loggedEvents.clear();
        save.accept(service);
        assertComplete(storedPolicy());
        assertThat(loggedEvents).singleElement().satisfies(e -> assertThat(e.getDetails()).isEqualTo(logged));
    }

    @Test
    void everySectionSaveLeavesACompleteStoredBundle() throws Exception {
        assertPartialSaveLeavesACompleteBundle(s -> s.saveEvictorPolicy(config, new EvictorPolicyDto(), "jane.doe"),
                "Evictor policy updated by jane.doe");
        SafetyGuardsDto noNamespaces = new SafetyGuardsDto();
        noNamespaces.setCriticalNamespaces(null);
        assertPartialSaveLeavesACompleteBundle(s -> s.saveSafetyGuards(config, noNamespaces, "jane.doe"),
                "Safety guards updated by jane.doe");
        assertPartialSaveLeavesACompleteBundle(s -> s.saveNodeOptimizerPolicy(config, new NodeOptimizerPolicyDto(), "jane.doe"),
                "Node optimizer policy updated by jane.doe");
        assertPartialSaveLeavesACompleteBundle(s -> s.saveRebalancerConfig(config, new RebalancerConfigDto(), "jane.doe"),
                "Rebalancer config updated by jane.doe");
        assertPartialSaveLeavesACompleteBundle(s -> s.setMode(config, "scoped", "jane.doe"),
                "Node automation scope changed to scoped by jane.doe");

        // Refused while not ready; once ready, their saves are complete too.
        readiness.setScaleOut(true);
        readiness.setNodeTemplates(true);
        assertPartialSaveLeavesACompleteBundle(s -> s.saveScaleOutPolicy(config, new ScaleOutPolicyDto(), "jane.doe"),
                "Scale-out policy updated by jane.doe");
        assertPartialSaveLeavesACompleteBundle(s -> s.saveNodeTemplate(config, "tpl-1", new NodeTemplateDto(), "jane.doe"),
                "Node template tpl-1 saved by jane.doe");
        assertPartialSaveLeavesACompleteBundle(s -> s.deleteNodeTemplate(config, "tpl-1", "jane.doe"),
                "Node template tpl-1 deleted by jane.doe");
    }

    @Test
    void aSafetyGuardsSaveWithoutNamespacesKeepsTheStoredList() throws Exception {
        storeRow(EksAutoscalingService.DT_POLICY,
                "{\"safetyGuards\":{\"criticalNamespaces\":[\"payments\",\"kube-system\",\"xamops-system\"]}}");
        SafetyGuardsDto body = new SafetyGuardsDto();
        body.setCriticalNamespaces(null);

        service.saveSafetyGuards(config, body, "jane.doe");

        List<String> namespaces = new ArrayList<>();
        storedPolicy().get("safetyGuards").get("criticalNamespaces").forEach(n -> namespaces.add(n.asText()));
        assertThat(namespaces).containsExactly("payments", "kube-system", "xamops-system");
    }

    @Test
    void aStoredRunningRebalancerIsSavedIdle_andNotReadySavesAreRefusedBeforeAnyWrite() throws Exception {
        storeRow(EksAutoscalingService.DT_POLICY, "{\"rebalancer\":{\"status\":\"running\",\"lastRebalanceAt\":\"2026-10-01T02:00:00Z\"}}");
        service.saveEvictorPolicy(config, new EvictorPolicyDto(), "jane.doe");
        assertThat(storedPolicy().get("rebalancer").get("status").asText()).isEqualTo("idle");
        assertThat(storedPolicy().get("rebalancer").get("lastRebalanceAt").asText()).isEqualTo("2026-10-01T02:00:00Z");

        cache.clear();
        loggedEvents.clear();
        assertThatThrownBy(() -> service.triggerRebalance(config, "jane.doe"))
                .isInstanceOfSatisfying(K8sAutomationRefusedException.class,
                        e -> assertThat(e.getReason()).isEqualTo(K8sAutomationReason.CONTROLLER_NOT_READY));
        EvictorPolicyDto live = new EvictorPolicyDto();
        live.setAnalyseOnly(false);
        assertThatThrownBy(() -> service.saveEvictorPolicy(config, live, "jane.doe"))
                .isInstanceOfSatisfying(K8sAutomationRefusedException.class,
                        e -> assertThat(e.getReason()).isEqualTo(K8sAutomationReason.ANALYSE_ONLY));
        assertThat(cache).as("no policy row written").isEmpty();
        assertThat(loggedEvents).as("no change recorded").isEmpty();
    }

    // ── Rebalance status ──────────────────────────────────────────────────

    @Test
    void anUnparseableRebalanceStatusIsUnknown_withTheMessageOnlyInTheLog() {
        storeRow(EksAutoscalingService.DT_REBALANCE_STATUS, "{\"status\": \"running\", \"secret\": oops");

        Map<String, Object> status = service.getRebalanceStatus(config);

        assertThat(status).containsExactly(Map.entry("status", "unknown"));
        assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.WARN).singleElement()
                .satisfies(e -> assertThat(e.getFormattedMessage()).contains("100"));
    }

    @Test
    void aMissingRebalanceStatusIsIdle() {
        assertThat(service.getRebalanceStatus(config)).containsEntry("status", "idle");
    }

    // ── Capabilities summary (D5) ─────────────────────────────────────────

    @Test
    void summarizeDerivesTheNodeAutomationState() {
        assertThat(service.summarize(config)).satisfies(s -> {
            assertThat(s.enabled()).isFalse();
            assertThat(s.mode()).isNull();
            assertThat(s.evictorAnalyseOnly()).isNull();
            assertThat(s.derivedState()).isEqualTo("OFF");
        });
        config.setAutoscalingEnabled(true);
        storeRow(EksAutoscalingService.DT_POLICY, "{\"autoscalerMode\":\"full\"}");
        assertThat(service.summarize(config)).satisfies(s -> {
            assertThat(s.mode()).isEqualTo("full");
            assertThat(s.evictorAnalyseOnly()).isTrue();
            assertThat(s.derivedState()).isEqualTo("ANALYSIS");
        });
        storeRow(EksAutoscalingService.DT_POLICY, "{\"autoscalerMode\":\"disabled\"}");
        assertThat(service.summarize(config).derivedState()).isEqualTo("OFF");
        readiness.setEvictorLive(true);
        storeRow(EksAutoscalingService.DT_POLICY, "{\"autoscalerMode\":\"full\",\"evictorPolicy\":{\"analyseOnly\":false}}");
        assertThat(service.summarize(config).derivedState()).isEqualTo("AUTOMATED");
    }

    @Test
    void readsUseTheResolvedConfigOnly() {
        service.getNodeFleetReport(config);
        service.getEvictorCandidates(config);
        verify(cacheRepository, never()).findByClusterConfigIdAndDataType(eq(7L), anyString());
    }
}
