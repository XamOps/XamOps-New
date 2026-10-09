package com.xammer.cloud.repository;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.xammer.cloud.domain.type.LobTextWriteMode;
import com.xammer.cloud.domain.type.PgTestSupport;
import com.xammer.cloud.service.CostDataSnapshotService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.LoggerFactory;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC 10 with convert-on-read on and the write mode {@code inline}: a read converts the legacy row to
 * inline JSON, keeps {@code last_updated}, creates no large object and leaves the old one in place.
 * The conditional UPDATE is a no-op once the row no longer holds the OID text it was read with.
 */
@DataJpaTest
@ContextConfiguration(classes = JpaSliceTestConfig.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "LOBTEXT_TEST_JDBC_URL", matches = ".+")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({CostDataSnapshotService.class, ConvertOnReadPgTestBase.Jackson.class})
class ConvertOnReadInlinePgTest extends ConvertOnReadPgTestBase {

    private static final String DATABASE = "lobtext_x_cor_inline";

    private static String url;

    private final ListAppender<ILoggingEvent> log = new ListAppender<>();
    private final Logger serviceLogger = (Logger) LoggerFactory.getLogger(CostDataSnapshotService.class);

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        url = register(registry, DATABASE, "inline", true);
    }

    @Override
    String url() {
        return url;
    }

    @BeforeEach
    void captureServiceLog() {
        log.start();
        serviceLogger.addAppender(log);
    }

    @AfterEach
    void releaseServiceLog() {
        serviceLogger.detachAppender(log);
    }

    @Test
    void theServiceSeesTheFlagOnInInlineMode() {
        assertServiceSees(LobTextWriteMode.INLINE, true);
    }

    @Test
    void aReadConvertsTheLegacyRowToInlineJsonAndKeepsLastUpdated() {
        String key = totalKey("300000000001");
        String ref = insertLegacyRow(key, TOTAL_JSON);
        long objects = objects();

        assertThat(snapshotService.readMonthlyTotal("300000000001", MONTH)).contains(1953.07);

        assertThat(raw(key)).isEqualTo(TOTAL_JSON);
        assertThat(lastUpdated(key)).isEqualTo(T0);
        assertThat(objects()).as("no large object created").isEqualTo(objects);
        assertThat(PgTestSupport.loExists(url, Long.parseLong(ref))).as("old object kept").isTrue();
        assertThat(warnings()).isEmpty();

        // The converted row reads the same, and a second read has nothing left to convert.
        assertThat(snapshotService.readMonthlyTotal("300000000001", MONTH)).contains(1953.07);
        assertThat(raw(key)).isEqualTo(TOTAL_JSON);
        assertThat(lastUpdated(key)).isEqualTo(T0);
    }

    @Test
    void aStaleOidTextUpdatesNoRow() {
        String key = totalKey("300000000002");
        String ref = insertLegacyRow(key, TOTAL_JSON);
        String stale = Long.toString(PgTestSupport.createLoText(url, TOTAL_JSON));

        assertThat(repository.inlineLegacyRow(key, stale, TOTAL_JSON)).isZero();

        assertThat(raw(key)).isEqualTo(ref);
        assertThat(lastUpdated(key)).isEqualTo(T0);
    }

    @Test
    void aRowRewrittenMeanwhileIsLeftAsTheWriterLeftIt() {
        String key = totalKey("300000000003");
        String ref = insertLegacyRow(key, TOTAL_JSON);
        PgTestSupport.setRaw(url, "cached_data", "cache_key", key, "json_data", "2000.0");   // concurrent writer

        assertThat(repository.inlineLegacyRow(key, ref, TOTAL_JSON)).isZero();

        assertThat(raw(key)).isEqualTo("2000.0");
        assertThat(lastUpdated(key)).isEqualTo(T0);
    }

    @Test
    void theCurrentOidTextUpdatesExactlyThatRow() {
        String key = totalKey("300000000004");
        String ref = insertLegacyRow(key, TOTAL_JSON);
        String other = totalKey("300000000005");
        insertRaw(other, ref);                                  // a second row holding the same text
        long objects = objects();

        assertThat(repository.inlineLegacyRow(key, ref, TOTAL_JSON)).isEqualTo(1);

        assertThat(raw(key)).isEqualTo(TOTAL_JSON);
        assertThat(lastUpdated(key)).isEqualTo(T0);
        assertThat(raw(other)).isEqualTo(ref);
        assertThat(objects()).isEqualTo(objects);
    }

    @Test
    void inlineRowsAndUnreadableOrOidShapedReferencesAreNotRewritten() {
        String inline = totalKey("300000000006");
        insertRaw(inline, "2500.5");
        String dangling = totalKey("300000000007");
        String danglingRef = Long.toString(PgTestSupport.danglingOid(url));
        insertRaw(dangling, danglingRef);
        String oidShaped = totalKey("300000000008");
        String target = Long.toString(PgTestSupport.createLoText(url, TOTAL_JSON));
        String oidShapedRef = insertLegacyRow(oidShaped, target);   // content is itself an OID text

        assertThat(snapshotService.readMonthlyTotal("300000000006", MONTH)).contains(2500.5);
        assertThat(snapshotService.readMonthlyTotal("300000000007", MONTH)).isEmpty();
        assertThat(snapshotService.resolvePayload(repository.findById(oidShaped).orElseThrow())).isEqualTo(target);

        assertThat(raw(inline)).isEqualTo("2500.5");
        assertThat(raw(dangling)).isEqualTo(danglingRef);
        assertThat(raw(oidShaped)).isEqualTo(oidShapedRef);
        assertThat(List.of(inline, dangling, oidShaped)).allSatisfy(k -> assertThat(lastUpdated(k)).isEqualTo(T0));
        assertThat(warnings()).isEmpty();
    }

    private List<ILoggingEvent> warnings() {
        return log.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
    }
}
