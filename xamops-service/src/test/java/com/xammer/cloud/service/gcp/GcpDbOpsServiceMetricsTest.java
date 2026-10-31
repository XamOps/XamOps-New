package com.xammer.cloud.service.gcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.MonitoredResource;
import com.google.cloud.monitoring.v3.MetricServiceClient;
import com.google.monitoring.v3.Aggregation;
import com.google.monitoring.v3.ListTimeSeriesRequest;
import com.google.monitoring.v3.Point;
import com.google.monitoring.v3.TimeInterval;
import com.google.monitoring.v3.TimeSeries;
import com.google.monitoring.v3.TypedValue;
import com.google.protobuf.Timestamp;
import com.xammer.cloud.config.DbOpsFeatureFlags;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.DbAgentConfig;
import com.xammer.cloud.dto.dbops.DbInstanceDto;
import com.xammer.cloud.dto.dbops.DbMetricsDto;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.DbAgentConfigRepository;
import com.xammer.cloud.repository.DbAgentDataCacheRepository;
import com.xammer.cloud.repository.DbAgentMetricsHistoryRepository;
import com.xammer.cloud.service.RedisCacheService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FEAT-002 (Phase 1b) correctness proofs for {@link GcpDbOpsService}'s Cloud Monitoring path:
 * COR-2 (label↔inventory match), COR-3 (Postgres connections from num_backends), COR-9 (free
 * storage, memory from tier RAM, ALIGN_RATE for DELTA metrics) and COR-17 (UTC timeseries window).
 *
 * <p>The gRPC {@code MetricServiceClient} is awkward to mock, so the pure helpers
 * ({@code matchInstanceKey}, {@code selectAligner}, {@code resolveTierRamMb}, {@code applyMetricValue})
 * are exercised directly and a mocked {@code MetricServiceClient} drives {@code fetchAndApplyMetric}
 * with hand-built {@link TimeSeries} protos for the end-to-end connections proof.
 */
class GcpDbOpsServiceMetricsTest {

    private static final String PROJECT = "proj";
    private static final String KEY = "proj:us-central1:mydb";

    private static TimeZone originalZone;

    private GcpClientProvider gcpClientProvider;
    private CloudAccountRepository cloudAccountRepository;
    private DbAgentConfigRepository dbAgentConfigRepository;
    private DbAgentMetricsHistoryRepository dbAgentMetricsHistoryRepository;
    private GcpDbOpsService service;

    @BeforeAll
    static void forceNonUtcZone() {
        originalZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
    }

    @AfterAll
    static void restoreZone() {
        TimeZone.setDefault(originalZone);
    }

    @BeforeEach
    void setUp() {
        gcpClientProvider = mock(GcpClientProvider.class);
        cloudAccountRepository = mock(CloudAccountRepository.class);
        dbAgentConfigRepository = mock(DbAgentConfigRepository.class);
        dbAgentMetricsHistoryRepository = mock(DbAgentMetricsHistoryRepository.class);
        service = new GcpDbOpsService(
                gcpClientProvider,
                cloudAccountRepository,
                mock(RedisCacheService.class),
                mock(CachedDataRepository.class),
                new ObjectMapper(),
                dbAgentConfigRepository,
                mock(DbAgentDataCacheRepository.class),
                dbAgentMetricsHistoryRepository,
                new DbOpsFeatureFlags(false));
    }

    // ── COR-2: label ↔ inventory key match ──────────────────────────────────

    @Test
    void matchesAllLabelFormats() {
        Set<String> keys = Set.of(KEY);
        assertThat(GcpDbOpsService.matchInstanceKey(keys, "mydb")).isEqualTo(KEY);
        assertThat(GcpDbOpsService.matchInstanceKey(keys, "proj:mydb")).isEqualTo(KEY);
        assertThat(GcpDbOpsService.matchInstanceKey(keys, KEY)).isEqualTo(KEY);
        assertThat(GcpDbOpsService.matchInstanceKey(keys, "otherdb")).isNull();
    }

    // ── COR-3: Postgres connections come from num_backends, MySQL from network/connections ──

    @Test
    void postgresConnectionsFromNumBackends() {
        String pgKey = "proj:us-central1:mypg";
        String mysqlKey = "proj:us-central1:mysql1";

        Map<String, DbMetricsDto> metricsMap = new HashMap<>();
        metricsMap.put(pgKey, newMetrics(pgKey));
        metricsMap.put(mysqlKey, newMetrics(mysqlKey));

        Map<String, DbInstanceDto> instByKey = new HashMap<>();
        instByKey.put(pgKey, instance(pgKey, "postgres", "db-custom-2-7680", 100));
        instByKey.put(mysqlKey, instance(mysqlKey, "mysql", "db-n1-standard-1", 100));

        TimeInterval interval = sevenDayInterval();

        // num_backends (avg 12) for the Postgres server
        MetricServiceClient numBackendsClient = clientReturning(
                series("mypg", 10.0, 12.0, 14.0));
        service.fetchAndApplyMetric(numBackendsClient, "projects/" + PROJECT, interval,
                "cloudsql.googleapis.com/database/postgresql/num_backends",
                PROJECT, metricsMap, instByKey, "numBackends");

        // network/connections (avg 7) for the MySQL server
        MetricServiceClient networkConnClient = clientReturning(
                series("mysql1", 7.0, 7.0, 7.0));
        service.fetchAndApplyMetric(networkConnClient, "projects/" + PROJECT, interval,
                "cloudsql.googleapis.com/database/network/connections",
                PROJECT, metricsMap, instByKey, "connections");

        assertThat(metricsMap.get(pgKey).getAvgConnections()).isEqualTo(12.0);
        assertThat(metricsMap.get(mysqlKey).getAvgConnections()).isEqualTo(7.0);
    }

    @Test
    void networkConnectionsDoNotPopulatePostgres() {
        String pgKey = "proj:us-central1:mypg";
        Map<String, DbMetricsDto> metricsMap = new HashMap<>();
        metricsMap.put(pgKey, newMetrics(pgKey));
        Map<String, DbInstanceDto> instByKey = new HashMap<>();
        instByKey.put(pgKey, instance(pgKey, "postgres", "db-custom-2-7680", 100));

        MetricServiceClient client = clientReturning(series("mypg", 50.0, 50.0, 50.0));
        service.fetchAndApplyMetric(client, "projects/" + PROJECT, sevenDayInterval(),
                "cloudsql.googleapis.com/database/network/connections",
                PROJECT, metricsMap, instByKey, "connections");

        // network/connections is MySQL/SQL-Server only — must not overwrite a Postgres server
        assertThat(metricsMap.get(pgKey).getAvgConnections()).isEqualTo(0.0);
    }

    // ── COR-9 / general: applyMetricValue numeric correctness ────────────────

    @Test
    void cpuUtilizationScaledToPercent() {
        DbMetricsDto dto = newMetrics(KEY);
        service.applyMetricValue(dto, "cpu", 0.85,
                "cloudsql.googleapis.com/database/cpu/utilization",
                instance(KEY, "postgres", "db-custom-2-7680", 100));
        assertThat(dto.getAvgCpuPercent()).isEqualTo(85.0);
    }

    @Test
    void freeStorageComputed() {
        DbMetricsDto dto = newMetrics(KEY);
        double usedBytes = 40.0 * 1024.0 * 1024.0 * 1024.0; // 40 GiB used
        service.applyMetricValue(dto, "diskBytesUsed", usedBytes,
                "cloudsql.googleapis.com/database/disk/bytes_used",
                instance(KEY, "postgres", "db-custom-2-7680", 100));
        assertThat(dto.getFreeStorageSpaceGb()).isEqualTo(60.0); // 100 allocated − 40 used
    }

    @Test
    void memoryUsesTierRam() {
        DbMetricsDto dto = newMetrics(KEY);
        service.applyMetricValue(dto, "memory", 0.25,
                "cloudsql.googleapis.com/database/memory/utilization",
                instance(KEY, "postgres", "db-custom-2-7680", 100));
        assertThat(dto.getAvgFreeableMemoryMb()).isEqualTo(5760.0); // 7680 * (1 − 0.25)
    }

    @Test
    void memoryUnresolvableTierLeftUnset() {
        DbMetricsDto dto = newMetrics(KEY);
        service.applyMetricValue(dto, "memory", 0.25,
                "cloudsql.googleapis.com/database/memory/utilization",
                instance(KEY, "postgres", "db-n1-standard-2", 100));
        // non-custom tier: RAM unknown — leave 0 instead of fabricating a 1 GB figure
        assertThat(dto.getAvgFreeableMemoryMb()).isEqualTo(0.0);
    }

    @Test
    void resolveTierRamMbParsesCustomTier() {
        assertThat(GcpDbOpsService.resolveTierRamMb("db-custom-2-7680")).isEqualTo(7680.0);
        assertThat(GcpDbOpsService.resolveTierRamMb("db-n1-standard-2")).isNull();
        assertThat(GcpDbOpsService.resolveTierRamMb(null)).isNull();
    }

    // ── COR-9: DELTA count metrics use ALIGN_RATE, gauges use ALIGN_MEAN ──────

    @Test
    void deltaMetricsUseAlignRate() {
        assertThat(GcpDbOpsService.selectAligner(
                "cloudsql.googleapis.com/database/disk/read_ops_count"))
                .isEqualTo(Aggregation.Aligner.ALIGN_RATE);
        assertThat(GcpDbOpsService.selectAligner(
                "cloudsql.googleapis.com/database/disk/write_ops_count"))
                .isEqualTo(Aggregation.Aligner.ALIGN_RATE);
        assertThat(GcpDbOpsService.selectAligner(
                "cloudsql.googleapis.com/database/network/received_bytes_count"))
                .isEqualTo(Aggregation.Aligner.ALIGN_RATE);
        assertThat(GcpDbOpsService.selectAligner(
                "cloudsql.googleapis.com/database/network/sent_bytes_count"))
                .isEqualTo(Aggregation.Aligner.ALIGN_RATE);
    }

    @Test
    void gaugeMetricsUseAlignMean() {
        assertThat(GcpDbOpsService.selectAligner(
                "cloudsql.googleapis.com/database/cpu/utilization"))
                .isEqualTo(Aggregation.Aligner.ALIGN_MEAN);
        assertThat(GcpDbOpsService.selectAligner(
                "cloudsql.googleapis.com/database/memory/utilization"))
                .isEqualTo(Aggregation.Aligner.ALIGN_MEAN);
        assertThat(GcpDbOpsService.selectAligner(
                "cloudsql.googleapis.com/database/postgresql/num_backends"))
                .isEqualTo(Aggregation.Aligner.ALIGN_MEAN);
        assertThat(GcpDbOpsService.selectAligner(
                "cloudsql.googleapis.com/database/disk/bytes_used"))
                .isEqualTo(Aggregation.Aligner.ALIGN_MEAN);
    }

    // ── COR-17: agent timeseries window computed in UTC under a non-UTC JVM zone ──

    @Test
    void timeseriesWindowUsesUtc() throws Exception {
        CloudAccount account = new CloudAccount();
        account.setId(42L);
        when(cloudAccountRepository.findByGcpProjectId(PROJECT)).thenReturn(Optional.of(account));

        DbAgentConfig agent = mock(DbAgentConfig.class);
        when(agent.getId()).thenReturn(7L);
        when(dbAgentConfigRepository.findByCloudAccountId(42L)).thenReturn(List.of(agent));

        ArgumentCaptor<LocalDateTime> sinceCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        when(dbAgentMetricsHistoryRepository
                .findByDbAgentIdAndDataTypeAndCollectedAtAfterOrderByCollectedAtAsc(
                        eq(7L), eq("db-instance-metrics"), sinceCaptor.capture()))
                .thenReturn(Collections.emptyList());

        service.getAgentTimeseries(PROJECT, 1).get();

        LocalDateTime captured = sinceCaptor.getValue();
        LocalDateTime expectedUtc = LocalDateTime.now(ZoneOffset.UTC).minusHours(1);
        long driftSeconds = Math.abs(ChronoUnit.SECONDS.between(captured, expectedUtc));
        assertThat(driftSeconds).isLessThan(30);

        // And it must NOT be the local-zone (IST = UTC+5:30) window, which differs by ~5.5h.
        LocalDateTime localWindow = LocalDateTime.now().minusHours(1);
        long localDrift = Math.abs(ChronoUnit.SECONDS.between(captured, localWindow));
        assertThat(localDrift).isGreaterThan(3600);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static DbMetricsDto newMetrics(String key) {
        DbMetricsDto dto = new DbMetricsDto();
        dto.setInstanceId(key);
        return dto;
    }

    private static DbInstanceDto instance(String key, String engine, String tier, int allocatedGb) {
        DbInstanceDto dto = new DbInstanceDto();
        dto.setInstanceId(key);
        dto.setEngine(engine);
        dto.setInstanceClass(tier);
        dto.setAllocatedStorage(allocatedGb);
        return dto;
    }

    private static TimeInterval sevenDayInterval() {
        Instant end = Instant.now();
        Instant start = end.minus(7, ChronoUnit.DAYS);
        return TimeInterval.newBuilder()
                .setStartTime(Timestamp.newBuilder().setSeconds(start.getEpochSecond()).build())
                .setEndTime(Timestamp.newBuilder().setSeconds(end.getEpochSecond()).build())
                .build();
    }

    private static TimeSeries series(String databaseIdLabel, double... points) {
        TimeSeries.Builder builder = TimeSeries.newBuilder()
                .setResource(MonitoredResource.newBuilder()
                        .putLabels("database_id", databaseIdLabel)
                        .build());
        for (double p : points) {
            builder.addPoints(Point.newBuilder()
                    .setValue(TypedValue.newBuilder().setDoubleValue(p).build())
                    .build());
        }
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private static MetricServiceClient clientReturning(TimeSeries... seriesList) {
        MetricServiceClient.ListTimeSeriesPagedResponse paged =
                mock(MetricServiceClient.ListTimeSeriesPagedResponse.class);
        when(paged.iterateAll()).thenReturn(List.of(seriesList));
        MetricServiceClient client = mock(MetricServiceClient.class);
        when(client.listTimeSeries(any(ListTimeSeriesRequest.class))).thenReturn(paged);
        return client;
    }
}
