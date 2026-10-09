package com.xammer.cloud.repository;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.xammer.cloud.domain.type.LegacyLobTextType;
import com.xammer.cloud.domain.type.PgTestSupport;
import com.xammer.cloud.service.gcp.GcpClientProvider;
import com.xammer.cloud.service.gcp.GcpCostDataSnapshotService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * AC 7 for xamops-service: {@link GcpCostDataSnapshotService#readMonthlyTotal} over a throwaway local
 * PostgreSQL. An LO-ref {@code GCP_MONTHLY_TOTAL} row reads as the cost the object holds, never the
 * OID; an unreadable reference is a miss with the type's single WARN and nothing from the service
 * (step 2.3: a null payload returns before {@code readValue}). Reads never change the row.
 */
@DataJpaTest
@ContextConfiguration(classes = JpaSliceTestConfig.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "LOBTEXT_TEST_JDBC_URL", matches = ".+")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({GcpCostDataSnapshotService.class, ConvertOnReadPgTestBase.Jackson.class})
class GcpSnapshotLegacyReadPgTest {

    private static final String DATABASE = "lobtext_x_gcp_read";
    private static final String PROJECT = "lobtext-gcp-project";
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 1, 2, 5);

    private static String url;

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        PgTestSupport.requireLocal();
        url = PgTestSupport.recreateDatabase(DATABASE);
        PgTestSupport.springProperties(url, "create-drop").forEach((k, v) -> registry.add(k, () -> v));
    }

    @Autowired
    private GcpCostDataSnapshotService snapshotService;

    @MockitoBean
    private GcpClientProvider gcpClientProvider;

    private final ListAppender<ILoggingEvent> log = new ListAppender<>();
    private final List<Logger> loggers = Stream.of(LegacyLobTextType.class, GcpCostDataSnapshotService.class)
            .map(c -> (Logger) LoggerFactory.getLogger(c)).toList();

    @BeforeEach
    void captureLogs() {
        log.start();
        loggers.forEach(l -> l.addAppender(log));
    }

    @AfterEach
    void releaseLogs() {
        loggers.forEach(l -> l.detachAppender(log));
    }

    @Test
    void aLegacyReferenceMonthlyTotalReadsAsTheCostItHolds() {
        YearMonth month = YearMonth.of(2026, 8);
        String key = GcpCostDataSnapshotService.keyMonthlyTotal(PROJECT, month);
        String ref = Long.toString(PgTestSupport.createLoText(url, "1953.07"));
        insertRaw(key, ref);
        long objects = PgTestSupport.loCount(url);

        assertThat(snapshotService.readMonthlyTotal(PROJECT, month)).contains(1953.07);

        assertThat(raw(key)).isEqualTo(ref);
        assertThat(lastUpdated(key)).isEqualTo(T0);
        assertThat(PgTestSupport.loCount(url)).isEqualTo(objects);
        assertThat(warnings()).isEmpty();
        verifyNoInteractions(gcpClientProvider);
    }

    @Test
    void anInlineMonthlyTotalStillReadsAsBefore() {
        YearMonth month = YearMonth.of(2026, 7);
        String key = GcpCostDataSnapshotService.keyMonthlyTotal(PROJECT, month);
        insertRaw(key, "2000.0");

        assertThat(snapshotService.readMonthlyTotal(PROJECT, month)).contains(2000.0);
        assertThat(raw(key)).isEqualTo("2000.0");
        assertThat(warnings()).isEmpty();
    }

    @Test
    void aDanglingReferenceIsAMissWithOneWarnAndDoesNotRaise() {
        YearMonth month = YearMonth.of(2026, 6);
        String key = GcpCostDataSnapshotService.keyMonthlyTotal(PROJECT, month);
        long dangling = PgTestSupport.danglingOid(url);
        insertRaw(key, Long.toString(dangling));

        assertThat(snapshotService.readMonthlyTotal(PROJECT, month)).isEmpty();

        assertThat(warnings()).singleElement().satisfies(e -> {
            assertThat(e.getLoggerName()).isEqualTo(LegacyLobTextType.class.getName());
            assertThat(e.getFormattedMessage()).isEqualTo("Unreadable legacy large-object reference table=cached_data"
                    + " column=json_data key=" + key + " oid=" + dangling + " reason=missing");
        });
        assertThat(raw(key)).isEqualTo(Long.toString(dangling));
        assertThat(lastUpdated(key)).isEqualTo(T0);
    }

    @Test
    void aNullPayloadIsAMissWithoutAnyLog() {
        YearMonth month = YearMonth.of(2026, 5);
        insertRaw(GcpCostDataSnapshotService.keyMonthlyTotal(PROJECT, month), null);
        insertRaw(GcpCostDataSnapshotService.keyMonthlySvc(PROJECT, month), null);

        assertThat(snapshotService.readMonthlyTotal(PROJECT, month)).isEmpty();
        assertThat(snapshotService.readBillingSvc(PROJECT, month)).isEmpty();
        assertThat(log.list).filteredOn(e -> e.getLevel().isGreaterOrEqual(Level.WARN)).isEmpty();
    }

    private List<ILoggingEvent> warnings() {
        return log.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
    }

    private static void insertRaw(String key, String raw) {
        PgTestSupport.execute(url, "INSERT INTO cached_data (cache_key, json_data, last_updated) VALUES (?, ?, ?)",
                key, raw, T0);
    }

    private static String raw(String key) {
        return PgTestSupport.rawText(url, "cached_data", "cache_key", key, "json_data");
    }

    private static LocalDateTime lastUpdated(String key) {
        return LocalDateTime.parse(PgTestSupport.queryString(url,
                "SELECT to_char(last_updated, 'YYYY-MM-DD\"T\"HH24:MI:SS') FROM cached_data WHERE cache_key = ?", key));
    }
}
