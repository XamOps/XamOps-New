package com.xammer.cloud.service.spot;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.AgentDataCache;
import com.xammer.cloud.domain.K8sSpotInterruptionEvent;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.dto.spot.SpotInterruptionsDto;
import com.xammer.cloud.dto.spot.SpotInterruptionsDto.Event;
import com.xammer.cloud.repository.AgentDataCacheRepository;
import com.xammer.cloud.repository.K8sSpotInterruptionEventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The read API service (design §B5, AC20): fleet null cases, truncation, DTO mapping, unknown
 * workloads, and handling-now freshness under the production {@code Asia/Kolkata} default zone.
 */
class SpotInterruptionReadServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");
    private static final Long CLUSTER = 100L;

    private final Map<String, AgentDataCache> cache = new HashMap<>();
    private final List<K8sSpotInterruptionEvent> rows = new ArrayList<>();
    private K8sSpotInterruptionEventRepository eventRepository;
    private SpotInterruptionReadService service;
    private KubernetesClusterConfig config;
    private TimeZone previousZone;
    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    @BeforeEach
    void setUp() {
        previousZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
        eventRepository = mock(K8sSpotInterruptionEventRepository.class);
        when(eventRepository.findByClusterConfigIdAndNoticedAtAfterOrderByNoticedAtDesc(anyLong(), any(), any()))
                .thenAnswer(inv -> {
                    Pageable page = inv.getArgument(2);
                    return rows.stream().limit(page.getPageSize()).toList();
                });
        AgentDataCacheRepository cacheRepository = mock(AgentDataCacheRepository.class);
        when(cacheRepository.findByClusterConfigIdAndDataType(anyLong(), anyString()))
                .thenAnswer(inv -> Optional.ofNullable(cache.get(inv.getArgument(0) + "|" + inv.getArgument(1))));
        service = new SpotInterruptionReadService(eventRepository, cacheRepository,
                SpotInterruptionProperties.defaults(), Clock.fixed(NOW, ZoneOffset.UTC));
        config = new KubernetesClusterConfig();
        config.setId(CLUSTER);
        config.setClusterName("prod-eks");
        logger = (Logger) LoggerFactory.getLogger(SpotInterruptionReadService.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        TimeZone.setDefault(previousZone);
        logger.detachAppender(logs);
    }

    private void store(String dataType, String json, Duration age) {
        AgentDataCache row = new AgentDataCache(CLUSTER, dataType, json, 1);
        row.setReceivedAt(ServerTime.serverLocal(NOW.minus(age)));
        cache.put(CLUSTER + "|" + dataType, row);
    }

    private static K8sSpotInterruptionEvent row(long id, String node, Instant noticedAt) {
        K8sSpotInterruptionEvent e = new K8sSpotInterruptionEvent();
        e.setId(id);
        e.setClusterConfigId(CLUSTER);
        e.setNodeName(node);
        e.setNodeCreatedAt(SpotFixtures.CREATED_AT);
        e.setNoticeKind(SpotNoticeRules.KIND_INTERRUPTION);
        e.setSources("nth-taint");
        e.setNoticedAt(noticedAt);
        e.setRecoveryStatus(SpotInterruptionMerge.TRACKING);
        return e;
    }

    // ── Fleet and coverage ───────────────────────────────────────────────

    @Test
    void withNoAgentDataTheFleetAndCoverageAreUnknown() {
        SpotInterruptionsDto dto = service.read(config, 30, 200);

        assertThat(dto.fleet()).isNull();
        assertThat(dto.coverage().agentSnapshotAt()).isNull();
        assertThat(dto.coverage().automationReportAt()).isNull();
        assertThat(dto.coverage().handlingNow()).isNull();
        assertThat(dto.events()).isEmpty();
        assertThat(dto.truncated()).isFalse();
        assertThat(dto.windowDays()).isEqualTo(30);
        assertThat(dto.retentionDays()).isEqualTo(365);
        assertThat(dto.recordingEnabled()).isTrue();
    }

    @Test
    void theFleetCountsSpotNodesOfTheLatestSnapshot() {
        store(SpotInterruptionTracker.K8S_RESOURCES, "{\"nodes\":[{\"name\":\"a\",\"capacityType\":\"spot\"},"
                + "{\"name\":\"b\",\"capacityType\":\"on-demand\"},{\"name\":\"c\",\"capacityType\":\"on-demand\"}]}",
                Duration.ofMinutes(2));

        SpotInterruptionsDto dto = service.read(config, 30, 200);

        assertThat(dto.fleet().totalNodes()).isEqualTo(3);
        assertThat(dto.fleet().spotNodes()).isEqualTo(1);
        assertThat(dto.fleet().spotSharePct()).isEqualTo(33);
        assertThat(dto.fleet().collectedAt()).isEqualTo("2026-10-24T11:58:00Z");
        assertThat(dto.coverage().agentSnapshotAt()).isEqualTo("2026-10-24T11:58:00Z");
    }

    @Test
    void zeroNodesGiveANullSpotShare() {
        store(SpotInterruptionTracker.K8S_RESOURCES, "{\"nodes\":[]}", Duration.ofMinutes(2));

        SpotInterruptionsDto dto = service.read(config, 30, 200);

        assertThat(dto.fleet().totalNodes()).isZero();
        assertThat(dto.fleet().spotSharePct()).isNull();
    }

    @Test
    void aSnapshotWithoutANodeListOrThatDoesNotParseGivesANullFleet() {
        store(SpotInterruptionTracker.K8S_RESOURCES, "{\"pods\":[]}", Duration.ofMinutes(2));
        assertThat(service.read(config, 30, 200).fleet()).isNull();

        store(SpotInterruptionTracker.K8S_RESOURCES, "{not json", Duration.ofMinutes(2));
        SpotInterruptionsDto dto = service.read(config, 30, 200);
        assertThat(dto.fleet()).isNull();
        assertThat(dto.coverage().agentSnapshotAt()).as("the snapshot still arrived").isNotNull();
    }

    @Test
    void aFreshReportServesItsHandlingDecision() {
        store(SpotInterruptionTracker.NODE_OPTIMIZER_REPORT,
                "{\"interruptionHandling\":{\"mode\":\"Simulate\",\"reason\":\"DRY_RUN is on\"}}", Duration.ofMinutes(1));

        SpotInterruptionsDto dto = service.read(config, 30, 200);

        assertThat(dto.coverage().handlingNow().mode()).isEqualTo("simulate");
        assertThat(dto.coverage().handlingNow().reason()).isEqualTo("DRY_RUN is on");
        assertThat(dto.coverage().automationReportAt()).isEqualTo("2026-10-24T11:59:00Z");
    }

    @Test
    void aFreshReportWithoutADecisionOrInAnalyseIsUnknown() {
        store(SpotInterruptionTracker.NODE_OPTIMIZER_REPORT, "{\"interruptionNotices\":[]}", Duration.ofMinutes(1));
        assertThat(service.read(config, 30, 200).coverage().handlingNow().mode()).isEqualTo("unknown");

        store(SpotInterruptionTracker.NODE_OPTIMIZER_REPORT,
                "{\"interruptionHandling\":{\"mode\":\"analyse\",\"reason\":\"x\"}}", Duration.ofMinutes(1));
        assertThat(service.read(config, 30, 200).coverage().handlingNow().mode()).isEqualTo("unknown");
    }

    @Test
    void aStaleReportServesNoHandlingButKeepsItsTime_AC20() {
        store(SpotInterruptionTracker.NODE_OPTIMIZER_REPORT,
                "{\"interruptionHandling\":{\"mode\":\"live\",\"reason\":\"on\"}}", Duration.ofMinutes(15));

        SpotInterruptionsDto dto = service.read(config, 30, 200);

        assertThat(dto.coverage().handlingNow()).isNull();
        assertThat(dto.coverage().automationReportAt()).isEqualTo("2026-10-24T11:45:00Z");
    }

    @Test
    void anElevenMinuteOldIstWallTimeReportIsStaleWithAUtcClock_AC20() {
        AgentDataCache report = new AgentDataCache(CLUSTER, SpotInterruptionTracker.NODE_OPTIMIZER_REPORT,
                "{\"interruptionHandling\":{\"mode\":\"live\",\"reason\":\"on\"}}", 1);
        report.setReceivedAt(LocalDateTime.ofInstant(NOW.minus(Duration.ofMinutes(11)), ZoneId.of("Asia/Kolkata")));
        cache.put(CLUSTER + "|" + SpotInterruptionTracker.NODE_OPTIMIZER_REPORT, report);

        SpotInterruptionsDto dto = service.read(config, 30, 200);

        assertThat(dto.coverage().handlingNow()).isNull();
        assertThat(dto.coverage().automationReportAt()).isEqualTo("2026-10-24T11:49:00Z").endsWith("Z");
    }

    // ── Events ───────────────────────────────────────────────────────────

    @Test
    void theWindowIsScopedByClusterAndOneExtraRowMarksTruncation() {
        rows.add(row(1, "a", NOW.minusSeconds(60)));
        rows.add(row(2, "b", NOW.minusSeconds(120)));
        rows.add(row(3, "c", NOW.minusSeconds(180)));

        SpotInterruptionsDto dto = service.read(config, 7, 2);

        assertThat(dto.truncated()).isTrue();
        assertThat(dto.events()).extracting(Event::nodeName).containsExactly("a", "b");
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(eventRepository).findByClusterConfigIdAndNoticedAtAfterOrderByNoticedAtDesc(
                eq(CLUSTER), eq(NOW.minus(Duration.ofDays(7))), page.capture());
        assertThat(page.getValue().getPageSize()).isEqualTo(3);

        assertThat(service.read(config, 7, 3).truncated()).isFalse();
    }

    @Test
    void aRowMapsToTheDtoWithUtcTimesAndNullsForUnknowns() throws Exception {
        K8sSpotInterruptionEvent e = row(12, "ip-10-0-1-23", NOW.minusSeconds(600));
        e.setNoticeKind(SpotNoticeRules.KIND_UNCONFIRMED);
        e.setSources("karpenter-event,node-removed");
        e.setInstanceType("t3.medium");
        e.setZone("ap-south-1a");
        e.setCapacityType("spot");
        e.setNodegroup("ng-spot-1");
        e.setNodeLastSeenAt(NOW.minusSeconds(900));
        e.setNodeRemovedAt(NOW.minusSeconds(600));
        e.setCordonedBeforeRemoval(true);
        e.setHandlingMode(SpotHandling.NOT_ELIGIBLE);
        e.setHandlingReason("live: XamOps drains only nodes with the legacy NTH label today");
        e.setAffectedWorkloads("[{\"namespace\":\"team-a\",\"kind\":\"Deployment\",\"name\":\"web\",\"podsOnNode\":2}]");
        e.setPodCount(2);
        e.setWorkloadsSnapshotAt(NOW.minusSeconds(900));
        e.setRecoveryStatus(SpotInterruptionMerge.RECOVERED);
        e.setRecoveredObservedAt(NOW.minusSeconds(600));
        rows.add(e);

        Event ev = service.read(config, 30, 200).events().get(0);

        assertThat(ev.id()).isEqualTo(12L);
        assertThat(ev.sources()).containsExactly("karpenter-event", "node-removed");
        assertThat(ev.nodeCreatedAt()).isEqualTo("2026-10-24T06:00:00Z");
        assertThat(ev.noticedAt()).isEqualTo("2026-10-24T11:50:00Z");
        assertThat(ev.nodeLastSeenAt()).isEqualTo("2026-10-24T11:45:00Z");
        assertThat(ev.handling().mode()).isEqualTo("not-eligible");
        assertThat(ev.handling().reason()).startsWith("live:");
        assertThat(ev.drain()).isNull();
        assertThat(ev.pending()).isNull();
        assertThat(ev.instanceId()).isNull();
        assertThat(ev.rebalanceNoticedAt()).isNull();
        assertThat(ev.workloads()).hasSize(1);
        assertThat(ev.workloads().get(0).name()).isEqualTo("web");
        assertThat(ev.workloadsTruncated()).isFalse();
        assertThat(ev.recovery().status()).isEqualTo("recovered");
        assertThat(ev.recovery().lowerSeconds()).isZero();
        assertThat(ev.recovery().upperSeconds()).isEqualTo(300L);

        JsonNode json = JSON.valueToTree(ev);
        assertThat(json.has("drain")).isTrue();
        assertThat(json.get("drain").isNull()).isTrue();
        assertThat(json.get("recovery").get("recoveredByFirstSighting").asBoolean()).isFalse();
    }

    @Test
    void drainAndPendingAreServedWhenRecorded() {
        K8sSpotInterruptionEvent e = row(5, "n", NOW.minusSeconds(60));
        e.setDrainStatus("simulated");
        e.setDrainReason("DRY_RUN");
        e.setDrainDryRun(true);
        e.setDrainFinishedAt(NOW.minusSeconds(30));
        e.setPendingFirstSeenAt(NOW.minusSeconds(50));
        e.setPendingLastSeenAt(NOW.minusSeconds(40));
        e.setPendingMaxPods(3);
        rows.add(e);

        Event ev = service.read(config, 30, 200).events().get(0);

        assertThat(ev.drain().status()).isEqualTo("simulated");
        assertThat(ev.drain().dryRun()).isTrue();
        assertThat(ev.drain().finishedAt()).isEqualTo("2026-10-24T11:59:30Z");
        assertThat(ev.pending().maxPods()).isEqualTo(3);
        assertThat(ev.pending().firstSeenAt()).isEqualTo("2026-10-24T11:59:10Z");
        assertThat(ev.handling()).as("no handling recorded").isNull();
    }

    @Test
    void unknownAndUnparseableWorkloadsAreNullNeverEmpty() {
        K8sSpotInterruptionEvent unknown = row(1, "a", NOW.minusSeconds(60));
        K8sSpotInterruptionEvent broken = row(2, "b", NOW.minusSeconds(120));
        broken.setAffectedWorkloads("{not json");
        broken.setPodCount(3);
        K8sSpotInterruptionEvent none = row(3, "c", NOW.minusSeconds(180));
        none.setAffectedWorkloads("[]");
        none.setPodCount(0);
        rows.addAll(List.of(unknown, broken, none));

        List<Event> events = service.read(config, 30, 200).events();
        service.read(config, 30, 200);

        assertThat(events.get(0).workloads()).isNull();
        assertThat(events.get(0).workloadsTruncated()).isNull();
        assertThat(events.get(1).workloads()).isNull();
        assertThat(events.get(1).workloadsTruncated()).isNull();
        assertThat(events.get(2).workloads()).isEmpty();
        assertThat(events.get(2).workloadsTruncated()).isFalse();
        assertThat(logs.list).filteredOn(l -> l.getLevel() == Level.WARN)
                .as("one WARN per unparseable row id, across reads")
                .hasSize(1);
    }

    @Test
    void aCappedCaptureIsMarkedTruncated() {
        K8sSpotInterruptionEvent e = row(1, "a", NOW.minusSeconds(60));
        e.setAffectedWorkloads("[{\"namespace\":\"n\",\"kind\":\"Deployment\",\"name\":\"web\",\"podsOnNode\":2}]");
        e.setPodCount(9);
        rows.add(e);

        assertThat(service.read(config, 30, 200).events().get(0).workloadsTruncated()).isTrue();
    }
}
