package com.xammer.lobtext.billops;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.xammer.billops.repository.BillOpsCachedDataRepository;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.domain.type.LegacyLobTextType;
import com.xammer.cloud.domain.type.LobTextWriteMode;
import com.xammer.cloud.domain.type.OidShapeGuard;
import com.xammer.cloud.domain.type.PgTestSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.engine.spi.SessionImplementor;
import org.hibernate.type.CustomType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static com.xammer.cloud.domain.type.PgTestSupport.NON_ASCII_CONTENT;
import static com.xammer.cloud.domain.type.PgTestSupport.VALID_REF_CONTENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * billops, inline write mode, set through the Spring property {@code xammer.lob-text.write-mode}
 * so that the base {@code application.properties} bridge (D4) is exercised: plain-text writes
 * (AC 16), refusal of OID-shaped values (AC 19) by the entity callback and by the type alone.
 */
@DataJpaTest
@ContextConfiguration(classes = LobTextBillopsPgConfig.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("lobtext-pg")
@EnabledIfEnvironmentVariable(named = "LOBTEXT_TEST_JDBC_URL", matches = ".+")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LegacyLobTextTypeInlinePgTest {

    private static final String DATABASE = "lobtext_b_inline";
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 1, 12, 0);
    private static final LocalDateTime T1 = LocalDateTime.of(2026, 10, 2, 9, 30);

    private static String url;

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        PgTestSupport.requireLocal();
        url = PgTestSupport.recreateDatabase(DATABASE);
        PgTestSupport.springProperties(url, "create-drop").forEach((k, v) -> registry.add(k, () -> v));
        registry.add(LobTextWriteMode.PROPERTY, () -> "inline");
    }

    @Autowired
    private BillOpsCachedDataRepository repository;

    @Autowired
    private EntityManagerFactory emf;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final List<Logger> loggers = new ArrayList<>();
    private ListAppender<ILoggingEvent> log;

    @BeforeEach
    void captureLogs() {
        log = new ListAppender<>();
        log.start();
        for (Class<?> source : new Class<?>[] {LegacyLobTextType.class, OidShapeGuard.class}) {
            Logger logger = (Logger) LoggerFactory.getLogger(source);
            logger.addAppender(log);
            loggers.add(logger);
        }
    }

    @AfterEach
    void releaseLogs() {
        loggers.forEach(l -> l.detachAppender(log));
        loggers.clear();
    }

    @Test
    void inlineModeReachesTheMappedTypeThroughTheBaseFileBridge() {
        assertThat(LobTextWriteMode.effective(emf)).isEqualTo(LobTextWriteMode.INLINE);
        assertThat(jsonDataType().writeMode()).isEqualTo(LobTextWriteMode.INLINE);
    }

    @Test
    void ac16InlineWritesStoreThePlainValueAndCreateNoObject() {
        String key = "ac16::inline";
        String value = NON_ASCII_CONTENT + " {\"v\":1}";
        long objects = PgTestSupport.loCount(url);

        repository.save(new CachedData(key, value, T0));
        assertThat(raw(key)).isEqualTo(value);
        assertThat(repository.findById(key)).get().extracting(CachedData::getJsonData).isEqualTo(value);

        repository.save(new CachedData(key, "16383", T1));      // all digits, below the OID range
        assertThat(raw(key)).isEqualTo("16383");
        assertThat(PgTestSupport.loCount(url)).isEqualTo(objects);
    }

    @Test
    void inlineModeStillReadsLegacyReferences() {
        String key = "inline::reads-legacy";
        long oid = PgTestSupport.createLoText(url, VALID_REF_CONTENT);
        PgTestSupport.execute(url, "INSERT INTO cached_data (cache_key, json_data, last_updated) VALUES (?, ?, ?)",
                key, Long.toString(oid), T0);

        assertThat(repository.findById(key)).get().extracting(CachedData::getJsonData).isEqualTo(VALID_REF_CONTENT);
        assertThat(raw(key)).isEqualTo(Long.toString(oid));
    }

    @Test
    void ac19PersistingAnOidShapedValueIsRefusedAndWritesNothing() {
        String key = "ac19::new";
        long objects = PgTestSupport.loCount(url);

        assertRefused(() -> repository.save(new CachedData(key, "16842", T0)));

        assertThat(PgTestSupport.rowExists(url, "cached_data", "cache_key", key)).isFalse();
        assertThat(PgTestSupport.loCount(url)).isEqualTo(objects);
        assertThat(warnings()).extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("Refused OID-shaped value table=cached_data key=" + key);
    }

    @Test
    void ac19UpdatingToAnOidShapedValueIsRefusedAndKeepsTheStoredRow() {
        String key = "ac19::existing";
        PgTestSupport.execute(url, "INSERT INTO cached_data (cache_key, json_data, last_updated) VALUES (?, ?, ?)",
                key, "{\"keep\":1}", T0);
        long objects = PgTestSupport.loCount(url);

        assertRefused(() -> repository.save(new CachedData(key, "4294967295", T1)));

        assertThat(raw(key)).isEqualTo("{\"keep\":1}");
        assertThat(PgTestSupport.queryString(url, "SELECT to_char(last_updated, 'YYYY-MM-DD HH24:MI')"
                + " FROM cached_data WHERE cache_key = ?", key)).isEqualTo("2026-10-01 12:00");
        assertThat(PgTestSupport.loCount(url)).isEqualTo(objects);
        assertThat(warnings()).extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("Refused OID-shaped value table=cached_data key=" + key);
    }

    @Test
    void theTypeAloneRefusesAnOidShapedValueWithoutAnyEntityCallback() {
        LegacyLobTextType type = jsonDataType();
        long objects = PgTestSupport.loCount(url);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        tx.executeWithoutResult(status -> {
            SessionImplementor session = entityManager.unwrap(SessionImplementor.class);
            session.doWork(c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT CAST(? AS text)")) {
                    assertThatThrownBy(() -> type.nullSafeSet(ps, "16842", 1, session))
                            .isInstanceOf(IllegalArgumentException.class);
                    type.nullSafeSet(ps, "16383", 1, session);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        assertThat(rs.getString(1)).isEqualTo("16383");
                    }
                }
            });
        });

        assertThat(PgTestSupport.loCount(url)).isEqualTo(objects);
        assertThat(warnings()).extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("Refused OID-shaped value table=cached_data column=json_data");
    }

    // ------------------------------------------------------------------ helpers

    private static void assertRefused(ThrowingCallable write) {
        Throwable thrown = catchThrowable(write);
        assertThat(thrown).as("the write must be refused").isNotNull();
        List<Throwable> chain = new ArrayList<>();
        for (Throwable t = thrown; t != null && !chain.contains(t); t = t.getCause()) {
            chain.add(t);
        }
        assertThat(chain).anySatisfy(t -> assertThat(t)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Refused OID-shaped value"));
    }

    private LegacyLobTextType jsonDataType() {
        SessionFactoryImplementor sf = emf.unwrap(SessionFactoryImplementor.class);
        CustomType<?> type = (CustomType<?>) sf.getMappingMetamodel()
                .getEntityDescriptor(CachedData.class).getPropertyType("jsonData");
        return (LegacyLobTextType) type.getUserType();
    }

    private List<ILoggingEvent> warnings() {
        return log.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
    }

    private static String raw(String key) {
        return PgTestSupport.rawText(url, "cached_data", "cache_key", key, "json_data");
    }
}
