package com.xammer.cloud.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.gcp.GcpClientProvider;
import com.xammer.cloud.service.gcp.GcpCostDataSnapshotService;
import com.xammer.cloud.service.gcp.GcpNetCostCacheMigration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The three startup jobs are off unless their flag is set (AC 11-13, FR-2.2, FR-2.3, design D9).
 *
 * <p>Each "default" case sets the flag field to the default written in its {@code @Value}
 * placeholder, resolved against an empty environment, so the test follows the code default. Off
 * means no Redis, repository, thread or cloud-client interaction, and one INFO naming the flag; on
 * restores today's behaviour.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StartupJobGateTest {

    private static final String AWS_THREAD = "cost-snapshot-startup";
    private static final String GCP_THREAD = "gcp-cost-snapshot-startup";

    @Mock CachedDataRepository cachedDataRepository;
    @Mock CloudAccountRepository cloudAccountRepository;
    @Mock RedisCacheService redisCacheService;
    @Mock AwsClientProvider awsClientProvider;
    @Mock CurSnapshotReader curSnapshotReader;
    @Mock GcpClientProvider gcpClientProvider;

    private final ListAppender<ILoggingEvent> log = new ListAppender<>();
    private final List<Logger> loggers = List.of(
            (Logger) LoggerFactory.getLogger(GcpNetCostCacheMigration.class),
            (Logger) LoggerFactory.getLogger(CostDataSnapshotService.class),
            (Logger) LoggerFactory.getLogger(GcpCostDataSnapshotService.class));

    @BeforeEach
    void captureLogs() {
        log.start();
        loggers.forEach(l -> l.addAppender(log));
    }

    @AfterEach
    void releaseLogs() {
        loggers.forEach(l -> l.detachAppender(log));
    }

    // ------------------------------------------------------------------ AC 11: GCP net-cost flush

    @Nested
    class GcpNetCostFlush {

        private static final String FLAG = "gcp.billing.net-cost-cache-migration.enabled";

        private GcpNetCostCacheMigration migration;
        private String markerKey;

        @BeforeEach
        void setUp() {
            migration = new GcpNetCostCacheMigration(cachedDataRepository, redisCacheService);
            markerKey = (String) ReflectionTestUtils.getField(GcpNetCostCacheMigration.class, "MARKER_KEY");
        }

        @Test
        void byDefaultItEvictsNothingDeletesNothingWritesNoMarkerAndLogsTheFlag() {
            assertThat(placeholder(GcpNetCostCacheMigration.class, "enabled")).isEqualTo("${" + FLAG + ":false}");
            ReflectionTestUtils.setField(migration, "enabled", codeDefault(GcpNetCostCacheMigration.class, "enabled"));

            migration.flushGrossCostCachesOnce();

            verifyNoInteractions(redisCacheService, cachedDataRepository);
            assertThat(messages(Level.INFO)).singleElement().asString()
                    .contains(FLAG).contains("no Redis eviction, DB delete or marker");
        }

        @Test
        void withTheFlagOnAndNoMarkerItFlushesAsTodayAndWritesTheMarkerLast() {
            ReflectionTestUtils.setField(migration, "enabled", true);
            when(cachedDataRepository.existsById(markerKey)).thenReturn(false);

            migration.flushGrossCostCachesOnce();

            verify(redisCacheService, times(8)).evictByPattern(anyString());
            InOrder order = inOrder(cachedDataRepository);
            order.verify(cachedDataRepository, times(18)).deleteByCacheKeyPrefix(anyString());
            order.verify(cachedDataRepository).save(argThat((CachedData c) -> markerKey.equals(c.getCacheKey())));
        }

        @Test
        void withTheFlagOnAndTheMarkerPresentItDoesNothing() {
            ReflectionTestUtils.setField(migration, "enabled", true);
            when(cachedDataRepository.existsById(markerKey)).thenReturn(true);

            migration.flushGrossCostCachesOnce();

            verifyNoInteractions(redisCacheService);
            verify(cachedDataRepository, never()).deleteByCacheKeyPrefix(anyString());
            verify(cachedDataRepository, never()).save(any());
        }
    }

    // ------------------------------------------------------------------ AC 13: AWS history refill

    @Nested
    class AwsHistoryRefill {

        private static final String FLAG = "aws.billing.history-snapshot-on-startup.enabled";

        private CostDataSnapshotService service;

        @BeforeEach
        void setUp() {
            service = new CostDataSnapshotService(cloudAccountRepository, cachedDataRepository, awsClientProvider,
                    new ObjectMapper(), curSnapshotReader);
        }

        @Test
        void byDefaultItStartsNoThreadCallsNoCostExplorerAndLogsTheFlag() {
            assertThat(placeholder(CostDataSnapshotService.class, "historySnapshotOnStartupEnabled"))
                    .isEqualTo("${" + FLAG + ":false}");
            ReflectionTestUtils.setField(service, "historySnapshotOnStartupEnabled",
                    codeDefault(CostDataSnapshotService.class, "historySnapshotOnStartupEnabled"));

            service.autoSnapshotOnStartup();

            assertThat(aliveThreads(AWS_THREAD)).isEmpty();
            verify(cloudAccountRepository, after(300).never()).findAll();
            verifyNoInteractions(awsClientProvider, cachedDataRepository);
            assertThat(messages(Level.INFO)).singleElement().asString()
                    .contains(FLAG).contains("POST /api/xamops/billops-admin/snapshot-cost-history");
        }

        @Test
        void withTheFlagOnItRefillsInTheBackgroundAsToday() throws InterruptedException {
            ReflectionTestUtils.setField(service, "historySnapshotOnStartupEnabled", true);
            CloudAccount account = new CloudAccount();
            account.setProvider("AWS");
            account.setAwsAccountId("123456789012");
            when(cloudAccountRepository.findAll()).thenReturn(List.of(account));
            YearMonth lastMonth = YearMonth.now().minusMonths(1);
            when(cachedDataRepository.existsById(anyString())).thenReturn(true);
            when(cachedDataRepository.existsById(CostDataSnapshotService.keyCfUsage("123456789012", lastMonth)))
                    .thenReturn(false);
            when(awsClientProvider.getCostExplorerClient(account)).thenThrow(new IllegalStateException("test: no CE"));

            service.autoSnapshotOnStartup();

            verify(cloudAccountRepository, timeout(2000)).findAll();
            verify(awsClientProvider, timeout(2000)).getCostExplorerClient(account);
            joinAll(AWS_THREAD);
            assertThat(messages(Level.INFO)).anyMatch(m -> m.contains("Auto-snapshot triggered on startup"));
        }
    }

    // ------------------------------------------------------------------ AC 12: GCP history refill

    @Nested
    class GcpHistoryRefill {

        private static final String FLAG = "gcp.billing.history-snapshot-on-startup.enabled";

        private GcpCostDataSnapshotService service;

        @BeforeEach
        void setUp() {
            service = new GcpCostDataSnapshotService(cloudAccountRepository, cachedDataRepository, gcpClientProvider,
                    new ObjectMapper());
        }

        @Test
        void byDefaultItStartsNoThreadCallsNoBigQueryAndLogsTheFlag() {
            assertThat(placeholder(GcpCostDataSnapshotService.class, "historySnapshotOnStartupEnabled"))
                    .isEqualTo("${" + FLAG + ":false}");
            ReflectionTestUtils.setField(service, "historySnapshotOnStartupEnabled",
                    codeDefault(GcpCostDataSnapshotService.class, "historySnapshotOnStartupEnabled"));

            service.autoSnapshotOnStartup();

            assertThat(aliveThreads(GCP_THREAD)).isEmpty();
            verify(cloudAccountRepository, after(300).never()).findAll();
            verifyNoInteractions(gcpClientProvider, cachedDataRepository);
            assertThat(messages(Level.INFO)).singleElement().asString().contains(FLAG).contains("no BigQuery call");
        }

        @Test
        void withTheFlagOnItRefillsInTheBackgroundAsToday() throws InterruptedException {
            ReflectionTestUtils.setField(service, "historySnapshotOnStartupEnabled", true);
            CloudAccount account = new CloudAccount();
            account.setProvider("GCP");
            account.setGcpProjectId("lobtext-test-project");
            when(cloudAccountRepository.findAll()).thenReturn(List.of(account));
            YearMonth lastMonth = YearMonth.now().minusMonths(1);
            when(cachedDataRepository.existsById(anyString())).thenReturn(true);
            when(cachedDataRepository.existsById(GcpCostDataSnapshotService.keyMonthlySvc("lobtext-test-project", lastMonth)))
                    .thenReturn(false);

            service.autoSnapshotOnStartup();

            verify(cloudAccountRepository, timeout(2000)).findAll();
            verify(gcpClientProvider, timeout(2000)).getBigQueryClient("lobtext-test-project");
            joinAll(GCP_THREAD);
            assertThat(messages(Level.INFO)).anyMatch(m -> m.contains("Auto-snapshot triggered on startup"));
        }
    }

    // ------------------------------------------------------------------ helpers

    /** The {@code @Value} placeholder on {@code field}. */
    private static String placeholder(Class<?> type, String field) {
        try {
            return type.getDeclaredField(field).getAnnotation(Value.class).value();
        } catch (NoSuchFieldException e) {
            throw new AssertionError(e);
        }
    }

    /** The placeholder's default, resolved against an environment with no properties at all. */
    private static boolean codeDefault(Class<?> type, String field) {
        return Boolean.parseBoolean(new MockEnvironment().resolveRequiredPlaceholders(placeholder(type, field)));
    }

    private List<String> messages(Level level) {
        return log.list.stream().filter(e -> e.getLevel() == level).map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static List<Thread> aliveThreads(String name) {
        return Thread.getAllStackTraces().keySet().stream().filter(t -> name.equals(t.getName()) && t.isAlive()).toList();
    }

    private static void joinAll(String name) throws InterruptedException {
        for (Thread t : aliveThreads(name)) {
            t.join(5000);
        }
        assertThat(aliveThreads(name)).as("background thread %s finished", name).isEmpty();
    }
}
