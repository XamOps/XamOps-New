package com.xammer.lobtext.billops;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.xammer.billops.repository.BillOpsCachedDataRepository;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.domain.type.LegacyLobTextType;
import com.xammer.cloud.domain.type.LobTextWriteMode;
import com.xammer.cloud.domain.type.PgTestSupport;
import com.xammer.cloud.domain.type.PgTestSupport.LoShapes;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.Session;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.type.CustomType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import javax.sql.rowset.serial.SerialClob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.xammer.cloud.domain.type.PgTestSupport.NON_ASCII_CONTENT;
import static com.xammer.cloud.domain.type.PgTestSupport.VALID_REF_CONTENT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * billops, legacy write mode (the default from the base {@code application.properties} bridge):
 * reads of legacy large-object references and inline values (AC 1-6), legacy writes (AC 16) and
 * the identical re-save (AC 14), against a throwaway local PostgreSQL. Committed state is
 * inspected over plain JDBC, so the class runs without a test transaction.
 */
@DataJpaTest
@ContextConfiguration(classes = LobTextBillopsPgConfig.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("lobtext-pg")
@EnabledIfEnvironmentVariable(named = "LOBTEXT_TEST_JDBC_URL", matches = ".+")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LegacyLobTextTypePgTest {

    private static final String DATABASE = "lobtext_b_legacy";
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 1, 12, 0);
    private static final LocalDateTime T1 = LocalDateTime.of(2026, 10, 2, 9, 30);

    private static String url;

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        PgTestSupport.requireLocal();
        url = PgTestSupport.recreateDatabase(DATABASE);
        PgTestSupport.springProperties(url, "create-drop").forEach((k, v) -> registry.add(k, () -> v));
        // No write-mode setting: the base application.properties bridge supplies the default.
    }

    @Autowired
    private BillOpsCachedDataRepository repository;

    @Autowired
    private EntityManagerFactory emf;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Environment environment;

    private Logger typeLogger;
    private ListAppender<ILoggingEvent> log;

    @BeforeEach
    void captureTypeLog() {
        PgTestSupport.grantTable(url, "cached_data");
        typeLogger = (Logger) LoggerFactory.getLogger(LegacyLobTextType.class);
        log = new ListAppender<>();
        log.start();
        typeLogger.addAppender(log);
    }

    @AfterEach
    void releaseTypeLog() {
        typeLogger.detachAppender(log);
    }

    // ------------------------------------------------------------------ safety and wiring

    @Test
    void runsOnTheThrowawayDatabaseInLegacyModeByDefault() throws SQLException {
        assertThat(environment.getActiveProfiles()).containsExactly("lobtext-pg");
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT current_database(), host(inet_server_addr())")) {
            rs.next();
            assertThat(rs.getString(1)).isEqualTo(DATABASE);
            assertThat(rs.getString(2)).isIn("127.0.0.1", "::1");
        }
        assertThat(LobTextWriteMode.effective(emf)).isEqualTo(LobTextWriteMode.LEGACY);
        assertThat(jsonDataType().writeMode()).isEqualTo(LobTextWriteMode.LEGACY);
    }

    // ------------------------------------------------------------------ reads (AC 1-6)

    @Test
    void ac1LegacyReferenceReadsDecodedThroughEveryFinderAndStaysUnchanged() {
        long oid = PgTestSupport.createLoText(url, VALID_REF_CONTENT);
        String key = "ac1::GCP_MONTHLY_TOTAL::proj::2026-09";
        insertRaw(key, Long.toString(oid), T0);
        long objects = PgTestSupport.loCount(url);

        assertThat(repository.findById(key)).get().extracting(CachedData::getJsonData).isEqualTo(VALID_REF_CONTENT);
        assertThat(repository.findAll()).filteredOn(c -> key.equals(c.getCacheKey()))
                .singleElement().extracting(CachedData::getJsonData).isEqualTo(VALID_REF_CONTENT);
        assertThat(repository.findAllById(List.of(key)))
                .singleElement().extracting(CachedData::getJsonData).isEqualTo(VALID_REF_CONTENT);
        assertThat(repository.findFirstByCacheKeyStartingWith("ac1::GCP_MONTHLY_TOTAL::"))
                .get().extracting(CachedData::getJsonData).isEqualTo(VALID_REF_CONTENT);

        assertThat(raw(key)).isEqualTo(Long.toString(oid));
        assertThat(lastUpdated(key)).isEqualTo(T0);
        assertThat(PgTestSupport.loCount(url)).isEqualTo(objects);
        assertThat(warnings()).isEmpty();
    }

    @Test
    void ac2InlineValuesReadBackUnchanged() {
        Map<String, String> stored = new LinkedHashMap<>();
        int i = 0;
        for (PgTestSupport.CandidateVector v : PgTestSupport.candidateVectors()) {
            if (!v.candidate()) {
                stored.put("ac2::" + (i++), v.value());
            }
        }
        stored.put("ac2::sql-null", null);
        stored.forEach((key, value) -> insertRaw(key, value, T0));

        Map<String, String> read = new HashMap<>();
        repository.findAllById(stored.keySet()).forEach(c -> read.put(c.getCacheKey(), c.getJsonData()));
        assertThat(read).containsExactlyInAnyOrderEntriesOf(stored);
        stored.forEach((key, value) -> {
            assertThat(repository.findById(key)).get().extracting(CachedData::getJsonData).isEqualTo(value);
            assertThat(raw(key)).isEqualTo(value);
        });
        assertThat(warnings()).isEmpty();
    }

    @Test
    void ac3MissingObjectReadsAsNullWithOneWarnNamingKeyAndOid() {
        long oid = PgTestSupport.danglingOid(url);
        String key = "ac3::missing";
        insertRaw(key, Long.toString(oid), T0);

        assertThat(repository.findById(key)).get().extracting(CachedData::getJsonData).isNull();
        assertThat(repository.findAllById(List.of(key))).singleElement().extracting(CachedData::getJsonData).isNull();

        assertThat(warnings()).singleElement().extracting(ILoggingEvent::getFormattedMessage)
                .isEqualTo("Unreadable legacy large-object reference table=cached_data column=json_data key="
                        + key + " oid=" + oid + " reason=missing");
        assertThat(raw(key)).isEqualTo(Long.toString(oid));
    }

    @Test
    void ac4OneReadWriteTransactionReadsEveryShapeAndStillCommits() {
        Fixture f = seedFixture("ac4");
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        Map<String, String> read = tx.execute(status -> {
            Session session = entityManager.unwrap(Session.class);
            session.doWork(c -> {
                assertThat(c.getAutoCommit()).isFalse();
                assertThat(c.isReadOnly()).isFalse();
                execute(c, "SET LOCAL ROLE " + PgTestSupport.READER_ROLE);
            });
            Map<String, String> values = readAllThroughFinders(f);
            session.doWork(c -> {
                execute(c, "RESET ROLE");
                assertThat(selectOne(c)).isEqualTo(1);
            });
            return values;
        });

        f.assertDecoded(read);
        f.assertWarnings(warnings());
        f.assertStoredTextUnchanged();
    }

    @Test
    void ac5ReadsWorkInsideAReadOnlyTransaction() {
        Fixture f = seedFixture("ac5ro");
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setReadOnly(true);

        Map<String, String> read = tx.execute(status -> {
            Session session = entityManager.unwrap(Session.class);
            session.doWork(c -> {
                assertThat(c.getAutoCommit()).isFalse();
                assertThat(queryText(c, "SHOW transaction_read_only")).isEqualTo("on");
                execute(c, "SET LOCAL ROLE " + PgTestSupport.READER_ROLE);
            });
            Map<String, String> values = readAllThroughFinders(f);
            session.doWork(c -> {
                execute(c, "RESET ROLE");
                assertThat(selectOne(c)).isEqualTo(1);
            });
            return values;
        });

        f.assertDecoded(read);
        f.assertWarnings(warnings());
        f.assertStoredTextUnchanged();
    }

    @Test
    void ac5ReadsWorkInAutoCommit() throws SQLException {
        Fixture f = seedFixture("ac5ac");
        Map<String, String> read = new HashMap<>();

        // Hibernate outside any transaction: the connection stays in auto-commit.
        EntityManager unmanaged = emf.createEntityManager();
        try {
            unmanaged.unwrap(Session.class).doWork(c -> assertThat(c.getAutoCommit()).isTrue());
            for (String key : f.expected.keySet()) {
                read.put(key, unmanaged.find(CachedData.class, key).getJsonData());
            }
            unmanaged.clear();
            unmanaged.createQuery("select c from CachedData c where c.cacheKey in :keys", CachedData.class)
                    .setParameter("keys", f.expected.keySet())
                    .getResultList()
                    .forEach(c -> assertThat(c.getJsonData()).isEqualTo(read.get(c.getCacheKey())));
            unmanaged.unwrap(Session.class).doWork(c -> assertThat(selectOne(c)).isEqualTo(1));
        } finally {
            unmanaged.close();
        }
        // A declared query method runs without a Spring transaction.
        assertThat(repository.findFirstByCacheKeyStartingWith(f.prefix + "valid"))
                .get().extracting(CachedData::getJsonData).isEqualTo(VALID_REF_CONTENT);

        // Superuser: the ungranted object is readable here.
        Map<String, String> expected = new HashMap<>(f.expected);
        expected.put(f.prefix + "ungranted", "{\"ungranted\":true}");
        assertThat(read).containsExactlyInAnyOrderEntriesOf(expected);

        // The permission path in auto-commit: the mapped type on a private reader-role connection.
        try (Connection c = PgTestSupport.connect(url)) {
            assertThat(c.getAutoCommit()).isTrue();
            execute(c, "SET ROLE " + PgTestSupport.READER_ROLE);
            assertThat(readThroughType(c, f.prefix + "ungranted")).isNull();
            assertThat(readThroughType(c, f.prefix + "valid")).isEqualTo(VALID_REF_CONTENT);
            execute(c, "RESET ROLE");
            assertThat(selectOne(c)).isEqualTo(1);
        }
        f.assertWarnings(warnings());
        f.assertStoredTextUnchanged();
    }

    @Test
    void ac6AugustWrittenObjectReadsBackIdenticalIncludingNonAscii() throws SQLException {
        String key = "ac6::august";
        try (Connection c = PgTestSupport.connect(url)) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO cached_data (cache_key, json_data, last_updated) VALUES (?, ?, ?)")) {
                ps.setString(1, key);
                ps.setClob(2, new SerialClob(NON_ASCII_CONTENT.toCharArray()));
                ps.setObject(3, T0);
                ps.executeUpdate();
            }
            c.commit();
        }
        String stored = raw(key);
        assertThat(LegacyLobTextType.isCandidate(stored)).as("August stores the OID as text: %s", stored).isTrue();

        assertThat(repository.findById(key)).get().extracting(CachedData::getJsonData).isEqualTo(NON_ASCII_CONTENT);
        assertThat(PgTestSupport.loText(url, Long.parseLong(stored))).isEqualTo(NON_ASCII_CONTENT);
        assertThat(raw(key)).isEqualTo(stored);
    }

    // ------------------------------------------------------------------ writes (AC 16, AC 14)

    @Test
    void ac16LegacyWritesStoreAnOidWhoseObjectHoldsTheValueAndKeepOldObjects() {
        String key = "ac16::legacy";
        String first = NON_ASCII_CONTENT + " {\"v\":1}";
        long objects = PgTestSupport.loCount(url);

        repository.save(new CachedData(key, first, T0));
        String firstRef = raw(key);
        assertThat(LegacyLobTextType.isCandidate(firstRef)).as("stored %s", firstRef).isTrue();
        assertThat(PgTestSupport.loCount(url)).isEqualTo(objects + 1);
        assertThat(PgTestSupport.loText(url, Long.parseLong(firstRef))).isEqualTo(first);
        assertThat(PgTestSupport.clobText(url, "cached_data", "cache_key", key, "json_data")).isEqualTo(first);
        assertThat(repository.findById(key)).get().extracting(CachedData::getJsonData).isEqualTo(first);

        repository.save(new CachedData(key, "second", T1));
        String secondRef = raw(key);
        assertThat(secondRef).isNotEqualTo(firstRef);
        assertThat(PgTestSupport.loText(url, Long.parseLong(secondRef))).isEqualTo("second");
        assertThat(PgTestSupport.loExists(url, Long.parseLong(firstRef))).as("previous object kept").isTrue();
        assertThat(PgTestSupport.loCount(url)).isEqualTo(objects + 2);

        repository.save(new CachedData(key, null, T1));
        assertThat(raw(key)).isNull();
        assertThat(PgTestSupport.loCount(url)).isEqualTo(objects + 2);
    }

    @Test
    void ac14IdenticalResaveKeepsTheStoredReferenceAndUpdatesLastUpdated() {
        String key = "ac14::resave";
        String payload = "{\"total\":1953.07}";
        long oid = PgTestSupport.createLoText(url, payload);
        insertRaw(key, Long.toString(oid), T0);
        long objects = PgTestSupport.loCount(url);

        repository.save(new CachedData(key, payload, T1));

        assertThat(raw(key)).isEqualTo(Long.toString(oid));
        assertThat(lastUpdated(key)).isEqualTo(T1);
        assertThat(PgTestSupport.loCount(url)).isEqualTo(objects);
    }

    // ------------------------------------------------------------------ fixture and helpers

    /** The AC 1-4 shapes under one key prefix, with the values a read as the reader role returns. */
    private final class Fixture {
        final String prefix;
        final LoShapes shapes;
        final Map<String, String> expected = new LinkedHashMap<>();
        final Map<String, String> stored = new LinkedHashMap<>();

        Fixture(String prefix) {
            this.prefix = prefix + "::";
            this.shapes = PgTestSupport.createLoShapes(url);
            put("valid", shapes.validRef(), VALID_REF_CONTENT);
            put("non-ascii", shapes.nonAsciiRef(), NON_ASCII_CONTENT);
            put("oid-shaped-content", shapes.oidShapedContentRef(), shapes.validRef()); // one resolution level
            put("dangling", shapes.danglingRef(), null);
            put("invalid-utf8", shapes.invalidUtf8Ref(), null);
            put("nul-byte", shapes.nulByteRef(), null);
            put("ungranted", shapes.ungrantedRef(), null);
            put("inline-zero", "0.0", "0.0");
            put("inline-json", "{\"x\":\"y\"}", "{\"x\":\"y\"}");
            put("inline-16383", "16383", "16383");
            put("inline-out-of-range", "4294967296", "4294967296");
            put("inline-empty", "", "");
            put("sql-null", null, null);
            stored.forEach((key, raw) -> insertRaw(key, raw, T0));
        }

        private void put(String name, String raw, String value) {
            stored.put(prefix + name, raw);
            expected.put(prefix + name, value);
        }

        void assertDecoded(Map<String, String> read) {
            assertThat(read).containsExactlyInAnyOrderEntriesOf(expected);
        }

        void assertWarnings(List<ILoggingEvent> warnings) {
            List<String> messages = warnings.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(messages).containsExactlyInAnyOrder(
                    warn("dangling", shapes.danglingRef(), "missing"),
                    warn("invalid-utf8", shapes.invalidUtf8Ref(), "invalid-utf8"),
                    warn("nul-byte", shapes.nulByteRef(), "invalid-utf8"),
                    warn("ungranted", shapes.ungrantedRef(), "permission"));
        }

        private String warn(String name, String oid, String reason) {
            return "Unreadable legacy large-object reference table=cached_data column=json_data key="
                    + prefix + name + " oid=" + oid + " reason=" + reason;
        }

        void assertStoredTextUnchanged() {
            stored.forEach((key, raw) -> assertThat(raw(key)).as(key).isEqualTo(raw));
            stored.keySet().forEach(key -> assertThat(lastUpdated(key)).as(key).isEqualTo(T0));
        }
    }

    private Fixture seedFixture(String prefix) {
        return new Fixture(prefix);
    }

    private Map<String, String> readAllThroughFinders(Fixture f) {
        Map<String, String> values = new HashMap<>();
        repository.findAllById(f.expected.keySet()).forEach(c -> values.put(c.getCacheKey(), c.getJsonData()));
        entityManager.clear();
        for (String key : f.expected.keySet()) {
            assertThat(repository.findById(key)).get().extracting(CachedData::getJsonData).isEqualTo(values.get(key));
        }
        assertThat(repository.findFirstByCacheKeyStartingWith(f.prefix + "valid"))
                .get().extracting(CachedData::getJsonData).isEqualTo(VALID_REF_CONTENT);
        return values;
    }

    private LegacyLobTextType jsonDataType() {
        SessionFactoryImplementor sf = emf.unwrap(SessionFactoryImplementor.class);
        CustomType<?> type = (CustomType<?>) sf.getMappingMetamodel()
                .getEntityDescriptor(CachedData.class).getPropertyType("jsonData");
        return (LegacyLobTextType) type.getUserType();
    }

    private String readThroughType(Connection c, String key) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT json_data FROM cached_data WHERE cache_key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return jsonDataType().nullSafeGet(rs, 1, emf.unwrap(SessionFactoryImplementor.class).getWrapperOptions());
            }
        }
    }

    private List<ILoggingEvent> warnings() {
        return new ArrayList<>(log.list.stream().filter(e -> e.getLevel() == Level.WARN).toList());
    }

    private static void insertRaw(String key, String raw, LocalDateTime lastUpdated) {
        PgTestSupport.execute(url, "INSERT INTO cached_data (cache_key, json_data, last_updated) VALUES (?, ?, ?)",
                key, raw, lastUpdated);
    }

    private static String raw(String key) {
        return PgTestSupport.rawText(url, "cached_data", "cache_key", key, "json_data");
    }

    private static LocalDateTime lastUpdated(String key) {
        String text = PgTestSupport.queryString(url, "SELECT to_char(last_updated, 'YYYY-MM-DD\"T\"HH24:MI:SS')"
                + " FROM cached_data WHERE cache_key = ?", key);
        return LocalDateTime.parse(text);
    }

    private static void execute(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static int selectOne(Connection c) throws SQLException {
        return Integer.parseInt(queryText(c, "SELECT 1"));
    }

    private static String queryText(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
