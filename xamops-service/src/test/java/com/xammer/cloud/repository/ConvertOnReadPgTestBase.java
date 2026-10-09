package com.xammer.cloud.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.type.LobTextWriteMode;
import com.xammer.cloud.domain.type.PgTestSupport;
import com.xammer.cloud.service.AwsClientProvider;
import com.xammer.cloud.service.CostDataSnapshotService;
import com.xammer.cloud.service.CurSnapshotReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared fixture for the convert-on-read PG tests (AC 10, design D13): a real
 * {@link CostDataSnapshotService} over a throwaway local PostgreSQL, with the AWS client and the CUR
 * reader mocked, so every read goes to {@code cached_data}. Rows are seeded and inspected over plain
 * JDBC in auto-commit, and the subclasses run without a test transaction (flag F7), because
 * {@link CachedDataRepository#inlineLegacyRow} commits in its own transaction.
 *
 * <p>Each subclass owns one database and one Spring context, and sets the write mode and the flag in
 * its {@code @DynamicPropertySource}.
 */
abstract class ConvertOnReadPgTestBase {

    static final String ACCOUNT = "123456789012";
    static final YearMonth MONTH = YearMonth.of(2026, 8);
    static final String TOTAL_JSON = "1953.07";
    static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 1, 3, 0);

    @Autowired
    CostDataSnapshotService snapshotService;

    @Autowired
    CachedDataRepository repository;

    @MockitoBean
    AwsClientProvider awsClientProvider;

    @MockitoBean
    CurSnapshotReader curSnapshotReader;

    /** The URL of this class's database. */
    abstract String url();

    /**
     * Registers the PG datasource for {@code database} and, when not null, the Hibernate write mode and
     * the convert-on-read flag. The test {@code application.properties} has no bridge line, so the
     * Hibernate key is set directly.
     */
    static String register(DynamicPropertyRegistry registry, String database, String writeMode, Boolean convertOnRead) {
        PgTestSupport.requireLocal();
        String url = PgTestSupport.recreateDatabase(database);
        PgTestSupport.springProperties(url, "create-drop").forEach((k, v) -> registry.add(k, () -> v));
        if (writeMode != null) {
            registry.add("spring.jpa.properties." + LobTextWriteMode.PROPERTY, () -> writeMode);
        }
        if (convertOnRead != null) {
            registry.add("xammer.lob-text.convert-on-read.enabled", () -> convertOnRead.toString());
        }
        return url;
    }

    /** The AWS_MONTHLY_TOTAL key for {@code account} and {@link #MONTH}. */
    static String totalKey(String account) {
        return CostDataSnapshotService.keyMonthlyTotal(account, MONTH);
    }

    /** A new legacy row for {@code key}: an object holding {@code json}, its OID stored as text. */
    String insertLegacyRow(String key, String json) {
        String ref = Long.toString(PgTestSupport.createLoText(url(), json));
        insertRaw(key, ref);
        return ref;
    }

    void insertRaw(String key, String raw) {
        PgTestSupport.execute(url(), "INSERT INTO cached_data (cache_key, json_data, last_updated) VALUES (?, ?, ?)",
                key, raw, T0);
    }

    String raw(String key) {
        return PgTestSupport.rawText(url(), "cached_data", "cache_key", key, "json_data");
    }

    LocalDateTime lastUpdated(String key) {
        return LocalDateTime.parse(PgTestSupport.queryString(url(),
                "SELECT to_char(last_updated, 'YYYY-MM-DD\"T\"HH24:MI:SS') FROM cached_data WHERE cache_key = ?", key));
    }

    long objects() {
        return PgTestSupport.loCount(url());
    }

    /** The service's convert-on-read inputs, read from the bean itself. */
    void assertServiceSees(LobTextWriteMode mode, boolean convertOnRead) {
        Object target = AopTestUtils.getTargetObject(snapshotService);
        assertThat(ReflectionTestUtils.getField(target, "writeMode")).isEqualTo(mode);
        assertThat(ReflectionTestUtils.getField(target, "convertOnReadEnabled")).isEqualTo(convertOnRead);
    }

    /** Asserts that reading a legacy row returned its value and left the row exactly as stored. */
    void assertReadLeavesTheRowUntouched(String account) {
        String key = totalKey(account);
        String ref = insertLegacyRow(key, TOTAL_JSON);
        long objects = objects();

        assertThat(snapshotService.readMonthlyTotal(account, MONTH)).contains(1953.07);
        assertThat(snapshotService.resolvePayload(repository.findById(key).orElseThrow())).isEqualTo(TOTAL_JSON);

        assertThat(raw(key)).isEqualTo(ref);
        assertThat(lastUpdated(key)).isEqualTo(T0);
        assertThat(objects()).isEqualTo(objects);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Jackson {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
