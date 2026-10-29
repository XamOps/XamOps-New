package com.xammer.cloud.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.config.K8sAutomationReadinessProperties;
import com.xammer.cloud.config.multitenancy.ImpersonationContext;
import com.xammer.cloud.domain.AgentEvent;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.dto.k8s.K8sCapabilitiesDto;
import com.xammer.cloud.dto.k8s.K8sCapabilitiesDto.Cluster;
import com.xammer.cloud.exception.K8sAutomationReason;
import com.xammer.cloud.exception.K8sAutomationRefusedException;
import com.xammer.cloud.repository.AgentDataCacheRepository;
import com.xammer.cloud.repository.AgentDataCacheRepository.TypeTimestamp;
import com.xammer.cloud.repository.AgentEventRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.K8sAutomationTestWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TimeZone;

import static com.xammer.cloud.security.K8sAutomationTestWorld.CLUSTER;
import static com.xammer.cloud.security.K8sAutomationTestWorld.TENANT_A_AWS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The capabilities response (design §6.2, §11.4, §17): thresholds on a fixed clock, the
 * automation-agent detection rule, per-cluster failure isolation, the readiness map, user details,
 * per-cluster write gates, the engine switch and the clock rule on a JVM in IST.
 */
class K8sCapabilitiesServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-10T09:30:00Z");

    private K8sAutomationTestWorld world;
    private AgentDataCacheRepository cacheRepository;
    private AgentEventRepository eventRepository;
    private EksAutoscalingService autoscalingService;
    private K8sAutomationReadinessProperties readiness;
    private MockEnvironment environment;
    private K8sCapabilitiesService service;
    private ClientUserDetails owner;
    private final List<TypeTimestamp> rows = new ArrayList<>();
    private TimeZone originalZone;
    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    record Row(Long clusterConfigId, String dataType, LocalDateTime collectedAt, Integer itemCount,
               String agentVersion) implements TypeTimestamp {
        public Long getClusterConfigId() { return clusterConfigId; }
        public String getDataType() { return dataType; }
        public LocalDateTime getCollectedAt() { return collectedAt; }
        public Integer getItemCount() { return itemCount; }
        public String getAgentVersion() { return agentVersion; }
    }

    @BeforeEach
    void setUp() {
        originalZone = TimeZone.getDefault();
        world = new K8sAutomationTestWorld();
        cacheRepository = mock(AgentDataCacheRepository.class);
        eventRepository = mock(AgentEventRepository.class);
        autoscalingService = mock(EksAutoscalingService.class);
        readiness = new K8sAutomationReadinessProperties();
        environment = new MockEnvironment();
        service = new K8sCapabilitiesService(world.accessService, cacheRepository, eventRepository, autoscalingService,
                readiness, environment, Clock.fixed(NOW, ZoneOffset.UTC));
        when(cacheRepository.findTypeTimestampsByClusterConfigIdIn(anyCollection())).thenAnswer(inv -> List.copyOf(rows));
        when(eventRepository.findFirstByClusterConfigIdAndAgentVersionEndingWithAndCreatedAtAfterOrderByCreatedAtDesc(
                anyLong(), anyString(), any())).thenReturn(Optional.empty());
        when(autoscalingService.summarize(any())).thenReturn(
                new EksAutoscalingService.NodeAutomationSummary(true, "full", true, "ANALYSIS",
                        EksAutoscalingService.EFFECTIVE_UNKNOWN));
        owner = world.addUser(10L, 1L, "owner@tenant-a.example", "XAMOPS", "ROLE_XAMOPS", List.of());
        logger = (Logger) LoggerFactory.getLogger(K8sCapabilitiesService.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        TimeZone.setDefault(originalZone);
        ImpersonationContext.clear();
        logger.detachAppender(logs);
    }

    private K8sCapabilitiesDto capabilities() {
        return service.capabilities(owner, TENANT_A_AWS);
    }

    private Cluster cluster(K8sCapabilitiesDto dto, String name) {
        return dto.clusters().stream().filter(c -> c.clusterName().equals(name)).findFirst().orElseThrow();
    }

    private static LocalDateTime utcWallClock(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static LocalDateTime serverWallClock(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }

    private void automationEvent(long configId, Instant createdAt, String version) {
        AgentEvent e = new AgentEvent(configId, "heartbeat", version, 0);
        e.setCreatedAt(serverWallClock(createdAt));
        when(eventRepository.findFirstByClusterConfigIdAndAgentVersionEndingWithAndCreatedAtAfterOrderByCreatedAtDesc(
                eq(configId), eq("-autoscaler"), any())).thenReturn(Optional.of(e));
    }

    // ── Shape ─────────────────────────────────────────────────────────────

    @Test
    void theResponseHasTheSchemaShape() throws Exception {
        rows.add(new Row(100L, "k8s-resources", utcWallClock(NOW.minus(Duration.ofMinutes(4))), 1, "v2.1.0"));
        K8sCapabilitiesDto dto = capabilities();

        assertThat(dto.schemaVersion()).isEqualTo(1);
        assertThat(dto.accountId()).isEqualTo(TENANT_A_AWS);
        assertThat(dto.generatedAt()).isEqualTo("2026-10-10T09:30:00Z");
        assertThat(dto.connection().accessLevel()).isEqualTo("READ_WRITE");
        assertThat(dto.connection().status()).isEqualTo("CONNECTED");
        assertThat(dto.user().canChangeAutomation()).isTrue();
        assertThat(dto.writeGate().allowed()).isTrue();
        assertThat(dto.clusters()).extracting(Cluster::clusterName).containsExactly(CLUSTER);
        Cluster c = dto.clusters().get(0);
        assertThat(c.registered()).isTrue();
        assertThat(c.dataSourceMode()).isEqualTo("agent");
        assertThat(c.dataSources()).singleElement().satisfies(s -> {
            assertThat(s.type()).isEqualTo("k8s-resources");
            assertThat(s.items()).isEqualTo(1);
            assertThat(s.displayed()).isTrue();
            assertThat(s.collectedAt()).isEqualTo("2026-10-10T09:26:00Z");
        });

        String json = new ObjectMapper().writeValueAsString(dto);
        assertThat(json).doesNotContain("cloudAccountId").doesNotContain("clusterConfigId")
                .doesNotContain("\"id\"").doesNotContain("\"100\"");
    }

    @Test
    void readinessListsAllKeysInOrder() {
        readiness.setEvictorLive(true);
        K8sCapabilitiesDto dto = capabilities();

        assertThat(dto.readiness().keySet()).containsExactly("vertical-rightsizing", "hpa-apply", "vpa-apply",
                "evictor-live", "rebalancer", "node-optimizer-actions", "scale-out", "node-templates",
                "spot-interruption-actions", "consolidation-actions");
        assertThat(dto.readiness().get("evictor-live")).satisfies(r -> {
            assertThat(r.ready()).isTrue();
            assertThat(r.reason()).isNull();
            assertThat(r.message()).isNull();
        });
        assertThat(dto.readiness().get("rebalancer")).satisfies(r -> {
            assertThat(r.ready()).isFalse();
            assertThat(r.reason()).isEqualTo("CONTROLLER_NOT_READY");
            assertThat(r.message()).isEqualTo("Rebalancing stays gated pending the benefit guard and a soak window; "
                    + "the ledger already prevents repeated drains of the same nodes.");
        });
    }

    @Test
    void hpaApplyReadinessReadsTheStagingFlag_notAReadWriteConnection() {
        // Regression (FEAT-001): hpa-apply is no longer derived from the read-write connection. A
        // read-write account with the standalone staging flag false reports hpa-apply NOT ready,
        // matching HpaApplyService's enforcement gate (the staging flag is an AND term there).
        assertThat(readiness.isHpaApply()).isFalse();

        K8sCapabilitiesDto dto = capabilities();

        assertThat(dto.connection().accessLevel()).isEqualTo("READ_WRITE");
        assertThat(dto.readiness().get("hpa-apply")).satisfies(r -> {
            assertThat(r.ready()).isFalse();
            assertThat(r.reason()).isEqualTo("CONTROLLER_NOT_READY");
            assertThat(r.message())
                    .isEqualTo("Automated HPA changes are disabled until the HPA manager passes safety validation.");
        });
        // Every other key reads straight from the properties, as before.
        assertThat(dto.readiness().get("vpa-apply").ready()).isFalse();
        assertThat(dto.readiness().get("vertical-rightsizing").ready()).isFalse();
    }

    @Test
    void hpaApplyReadinessIsReadyOnlyWhenTheStagingFlagIsTrue() {
        // Flip the staging flag: the key now reports ready, regardless of the connection access level.
        readiness.setHpaApply(true);

        K8sCapabilitiesDto dto = capabilities();

        assertThat(dto.connection().accessLevel()).isEqualTo("READ_WRITE");
        assertThat(dto.readiness().get("hpa-apply")).satisfies(r -> {
            assertThat(r.ready()).isTrue();
            assertThat(r.reason()).isNull();
            assertThat(r.message()).isNull();
        });
    }

    @Test
    void hpaApplyReadinessIsFalseForAReadOnlyConnection_withTheStagingFlagFalse() {
        world.tenantA.setAccessType("read-only");
        assertThat(readiness.isHpaApply()).isFalse();

        K8sCapabilitiesDto dto = capabilities();

        assertThat(dto.connection().accessLevel()).isEqualTo("READ_ONLY");
        assertThat(dto.readiness().get("hpa-apply")).satisfies(r -> {
            assertThat(r.ready()).isFalse();
            assertThat(r.reason()).isEqualTo("CONTROLLER_NOT_READY");
            assertThat(r.message())
                    .isEqualTo("Automated HPA changes are disabled until the HPA manager passes safety validation.");
        });
    }

    // ── XamOps agent thresholds ───────────────────────────────────────────

    @Test
    void theXamopsAgentIsConnectedWithin15MinutesOfItsLastData() {
        rows.add(new Row(100L, "k8s-resources", utcWallClock(NOW.minus(Duration.ofMinutes(15))), 1, "v2.1.0"));
        assertThat(cluster(capabilities(), CLUSTER).agent()).satisfies(a -> {
            assertThat(a.status()).isEqualTo("CONNECTED");
            assertThat(a.version()).isEqualTo("v2.1.0");
            assertThat(a.lastDataAt()).isEqualTo("2026-10-10T09:15:00Z");
        });

        rows.clear();
        rows.add(new Row(100L, "k8s-resources", utcWallClock(NOW.minus(Duration.ofMinutes(15)).minusSeconds(1)), 1, "v2.1.0"));
        assertThat(cluster(capabilities(), CLUSTER).agent().status()).isEqualTo("STALE");

        rows.clear();
        rows.add(new Row(100L, "trivy-vulnerabilities", utcWallClock(NOW), 3, "v2.1.0"));
        assertThat(cluster(capabilities(), CLUSTER).agent().status()).isEqualTo("NOT_INSTALLED");
    }

    // ── Automation agent detection ────────────────────────────────────────

    @Test
    void theAutomationAgentComesFromItsHeartbeatEventsWithin24Hours() {
        automationEvent(100L, NOW.minus(Duration.ofMinutes(4)), "1.0.0-autoscaler");
        assertThat(cluster(capabilities(), CLUSTER).automationAgent()).satisfies(a -> {
            assertThat(a.status()).isEqualTo("CONNECTED");
            assertThat(a.version()).isEqualTo("1.0.0-autoscaler");
            assertThat(a.lastSeenAt()).isEqualTo("2026-10-10T09:26:00Z");
        });

        automationEvent(100L, NOW.minus(Duration.ofHours(3)), "1.0.0-autoscaler");
        assertThat(cluster(capabilities(), CLUSTER).automationAgent().status()).isEqualTo("STALE");

        ArgumentCaptor<LocalDateTime> since = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(eventRepository, org.mockito.Mockito.atLeastOnce())
                .findFirstByClusterConfigIdAndAgentVersionEndingWithAndCreatedAtAfterOrderByCreatedAtDesc(
                        eq(100L), eq("-autoscaler"), since.capture());
        assertThat(since.getValue()).isEqualTo(serverWallClock(NOW.minus(Duration.ofHours(24))));
    }

    @Test
    void withoutARecentEventACacheRowIsStale_andNothingIsNotInstalled() {
        rows.add(new Row(100L, "autoscaling-metrics", utcWallClock(NOW.minus(Duration.ofDays(3))), 1, "1.0.0-autoscaler"));
        assertThat(cluster(capabilities(), CLUSTER).automationAgent()).satisfies(a -> {
            assertThat(a.status()).isEqualTo("STALE");
            assertThat(a.lastSeenAt()).isEqualTo("2026-10-07T09:30:00Z");
        });

        rows.clear();
        rows.add(new Row(100L, "k8s-resources", utcWallClock(NOW), 1, "v2.1.0"));
        K8sCapabilitiesDto dto = capabilities();
        assertThat(cluster(dto, CLUSTER).automationAgent().status()).isEqualTo("NOT_INSTALLED");
        assertThat(cluster(dto, CLUSTER).nodeAutomation().derivedState()).isEqualTo("NOT_INSTALLED");
    }

    @Test
    void lastAgentCheckinIsNeverUsedForTheAutomationAgent() {
        world.tenantAConfig.setLastAgentCheckin(serverWallClock(NOW));
        world.tenantAConfig.setAgentVersion("1.0.0-autoscaler");
        assertThat(cluster(capabilities(), CLUSTER).automationAgent().status()).isEqualTo("NOT_INSTALLED");
    }

    @Test
    void aPerClusterFailureIsUnknownForThatClusterOnly() {
        KubernetesClusterConfig second = world.addConfig(101L, K8sAutomationTestWorld.TENANT_A_DB_ID, "eu-eks");
        automationEvent(101L, NOW.minus(Duration.ofMinutes(1)), "1.0.0-autoscaler");
        when(eventRepository.findFirstByClusterConfigIdAndAgentVersionEndingWithAndCreatedAtAfterOrderByCreatedAtDesc(
                eq(100L), anyString(), any())).thenThrow(new IllegalStateException("db timeout"));

        K8sCapabilitiesDto dto = capabilities();

        assertThat(cluster(dto, CLUSTER).automationAgent().status()).isEqualTo("UNKNOWN");
        assertThat(cluster(dto, second.getClusterName()).automationAgent().status()).isEqualTo("CONNECTED");
    }

    @Test
    void aFailedCacheLookupIsUnknownWithoutFailingTheResponse() {
        when(cacheRepository.findTypeTimestampsByClusterConfigIdIn(anyCollection()))
                .thenThrow(new IllegalStateException("db timeout"));
        Cluster c = cluster(capabilities(), CLUSTER);
        assertThat(c.agent().status()).isEqualTo("UNKNOWN");
        assertThat(c.automationAgent().status()).isEqualTo("UNKNOWN");
    }

    // ── User and write gates ──────────────────────────────────────────────

    @Test
    void theUserDetailIsSetForEachRefusalKind() {
        owner = world.addUser(20L, 1L, "member@tenant-a.example", "XAMOPS", "ROLE_XAMOPS", List.of("cloudops-cloudk8s"));
        assertThat(capabilities().user()).satisfies(u -> {
            assertThat(u.canChangeAutomation()).isFalse();
            assertThat(u.reasons()).containsExactly("NO_USER_PERMISSION");
            assertThat(u.detail()).isEqualTo("TAB_GRANT");
        });

        owner = world.addUser(21L, 1L, "billing@tenant-a.example", "BILLOPS", "ROLE_BILLOPS", List.of());
        assertThat(capabilities().user().detail()).isEqualTo("ROLE");

        owner = world.addUser(22L, 1L, "lead@tenant-a.example", "XAMOPS", "ROLE_XAMOPS", List.of());
        ImpersonationContext.setImpersonatedUserId(22L);
        K8sCapabilitiesDto impersonated = capabilities();
        assertThat(impersonated.user().detail()).isEqualTo("IMPERSONATION");
        assertThat(impersonated.writeGate().reasons()).containsExactly("NO_USER_PERMISSION");
        ImpersonationContext.clear();

        owner = world.addUser(23L, 1L, "demo.xamops@xammer.in", "XAMOPS", "ROLE_XAMOPS", List.of());
        assertThat(capabilities().user()).satisfies(u -> {
            assertThat(u.reasons()).containsExactly("DEMO");
            assertThat(u.detail()).isNull();
        });
    }

    @Test
    void twoEntitledRowsWithDifferentAccess_topLevelIsStricter_clustersUseTheirOwner() {
        // The leading-zero quirk: tenant A's number exists padded (row 7, read-write) and raw (row 8, read-only).
        CloudAccount raw = world.addAccount(8L, "11111111111", 1L, "read-only", "CONNECTED");
        world.tenantA.setAwsAccountId("011111111111");
        world.addConfig(102L, raw.getId(), "raw-eks");

        K8sCapabilitiesDto dto = service.capabilities(owner, "11111111111");

        assertThat(dto.connection().accessLevel()).isEqualTo("READ_ONLY");
        assertThat(dto.writeGate().allowed()).isFalse();
        assertThat(dto.writeGate().reasons()).containsExactly("READ_ONLY_CONNECTION");
        assertThat(cluster(dto, CLUSTER).writeGate().allowed()).isTrue();
        assertThat(cluster(dto, CLUSTER).connection().accessLevel()).isEqualTo("READ_WRITE");
        assertThat(cluster(dto, "raw-eks").writeGate().reasons()).containsExactly("READ_ONLY_CONNECTION");
    }

    @Test
    void aReadOnlyUnverifiedAccountReportsBothGates() {
        world.tenantA.setAccessType(null);
        world.tenantA.setStatus("PENDING");
        K8sCapabilitiesDto dto = capabilities();
        assertThat(dto.connection().accessLevel()).isEqualTo("READ_ONLY");
        assertThat(dto.connection().status()).isEqualTo("PENDING");
        assertThat(dto.writeGate().reasons()).containsExactly("READ_ONLY_CONNECTION", "CONNECTION_NOT_VERIFIED");
    }

    @Test
    void notEntitledIsRefused() {
        assertThatThrownBy(() -> service.capabilities(owner, K8sAutomationTestWorld.TENANT_B_AWS))
                .isInstanceOfSatisfying(K8sAutomationRefusedException.class,
                        e -> assertThat(e.getReason()).isEqualTo(K8sAutomationReason.NO_ACCOUNT_ACCESS));
    }

    // ── Vertical engine switch ────────────────────────────────────────────

    @Test
    void theEngineSwitchIsReadByName_absentAndUnparseableAreOff() {
        assertThat(capabilities().verticalEngine().enabled()).isFalse();
        environment.setProperty("xamops.automation.vertical.enabled", "true");
        assertThat(capabilities().verticalEngine().enabled()).isTrue();
        environment.setProperty("xamops.automation.vertical.enabled", "false");
        assertThat(capabilities().verticalEngine().enabled()).isFalse();
        environment.setProperty("xamops.automation.vertical.enabled", "maybe");
        assertThat(capabilities().verticalEngine().enabled()).isFalse();
        assertThat(capabilities().verticalEngine().enabled()).isFalse();
        assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.WARN).singleElement()
                .satisfies(e -> assertThat(e.getFormattedMessage()).contains("xamops.automation.vertical.enabled"));
    }

    // ── Data sources and node automation ──────────────────────────────────

    @Test
    void dataSourcesCarryTheDisplayedFlags() {
        world.tenantAConfig.setKubescapeEnabled(false);
        world.tenantAConfig.setOpencostEnabled(true);
        rows.add(new Row(100L, "kubescape-compliance", utcWallClock(NOW), 22, "v2.1.0"));
        rows.add(new Row(100L, "opencost-cost", utcWallClock(NOW), 5, "v2.1.0"));
        rows.add(new Row(100L, "karpenter-data", utcWallClock(NOW), 1, "v2.1.0"));

        assertThat(cluster(capabilities(), CLUSTER).dataSources())
                .extracting(s -> s.type() + "=" + s.displayed())
                .containsExactly("karpenter-data=false", "kubescape-compliance=false", "opencost-cost=true");
    }

    @Test
    void nodeAutomationComesFromTheServiceSummary() {
        automationEvent(100L, NOW.minus(Duration.ofMinutes(1)), "1.0.0-autoscaler");
        assertThat(cluster(capabilities(), CLUSTER).nodeAutomation()).satisfies(n -> {
            assertThat(n.enabled()).isTrue();
            assertThat(n.mode()).isEqualTo("full");
            assertThat(n.evictorAnalyseOnly()).isTrue();
            assertThat(n.derivedState()).isEqualTo("ANALYSIS");
            // The additive effective-gate signal flows straight from the summary (BUG-1).
            assertThat(n.effectiveActing()).isEqualTo(EksAutoscalingService.EFFECTIVE_UNKNOWN);
        });
    }

    @Test
    void nodeAutomationCarriesTheEffectiveActingSignalFromTheSummary() {
        automationEvent(100L, NOW.minus(Duration.ofMinutes(1)), "1.0.0-autoscaler");
        when(autoscalingService.summarize(any())).thenReturn(
                new EksAutoscalingService.NodeAutomationSummary(true, "full", false, "AUTOMATED",
                        EksAutoscalingService.EFFECTIVE_NOT_ACTING));
        assertThat(cluster(capabilities(), CLUSTER).nodeAutomation().effectiveActing())
                .isEqualTo(EksAutoscalingService.EFFECTIVE_NOT_ACTING);
    }

    // ── Clock rule ────────────────────────────────────────────────────────

    @Test
    void onAJvmInIstBothClocksAreConvertedBeforeComparing() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
        // The agent sends collectedAt as UTC wall-clock time; agent_events.created_at is server time.
        rows.add(new Row(100L, "k8s-resources", utcWallClock(NOW.minus(Duration.ofMinutes(4))), 1, "v2.1.0"));
        AgentEvent heartbeat = new AgentEvent(100L, "heartbeat", "1.0.0-autoscaler", 0);
        heartbeat.setCreatedAt(LocalDateTime.ofInstant(NOW.minus(Duration.ofMinutes(4)), ZoneId.of("Asia/Kolkata")));
        when(eventRepository.findFirstByClusterConfigIdAndAgentVersionEndingWithAndCreatedAtAfterOrderByCreatedAtDesc(
                eq(100L), eq("-autoscaler"), any())).thenReturn(Optional.of(heartbeat));

        Cluster c = cluster(capabilities(), CLUSTER);

        assertThat(c.agent().status()).isEqualTo("CONNECTED");
        assertThat(c.agent().lastDataAt()).isEqualTo("2026-10-10T09:26:00Z");
        assertThat(c.automationAgent().status()).isEqualTo("CONNECTED");
        assertThat(c.automationAgent().lastSeenAt()).isEqualTo("2026-10-10T09:26:00Z");
    }
}
