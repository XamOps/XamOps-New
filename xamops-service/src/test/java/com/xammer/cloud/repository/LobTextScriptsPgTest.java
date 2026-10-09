package com.xammer.cloud.repository;

import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.domain.type.LobTextWriteMode;
import com.xammer.cloud.domain.type.PgTestSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-26 to AC-29 for the manual cutover SQL in {@code scripts/db/lob-text/}: the scripts are run by
 * the real psql against a throwaway local PostgreSQL, and their NOTICE output contract is parsed.
 *
 * <p>Every fixture is seeded and inspected over plain JDBC in auto-commit (flag F7), because psql runs
 * in its own session and cannot see an uncommitted test transaction; the class therefore runs without a
 * test transaction. The Spring slice is here for one reason: the watermark case needs a real
 * {@code legacy}-mode write through {@code LegacyLobTextType} to mint a new large object.
 *
 * <p>Runs per writing script (design §7.2): (1) no confirmation, (2) the {@code fail_after_convert}
 * rehearsal hook, (3) a normal run, (4) a second run, (5) the rollback.
 */
@DataJpaTest
@ContextConfiguration(classes = JpaSliceTestConfig.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "LOBTEXT_TEST_JDBC_URL", matches = ".+")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LobTextScriptsPgTest {

    private static final String DATABASE = "lobtext_x_scripts";
    private static final Path SCRIPTS = Path.of("..", "scripts", "db", "lob-text").toAbsolutePath().normalize();
    private static final String CENSUS = "census-lob-text.sql";
    private static final String NORMALIZE = "normalize-lob-text-columns.sql";
    private static final String ROLLBACK = "rollback-lob-text.sql";
    private static final String DEV_OPS = "dev-ops-script-lob-to-inline.sql";

    private static final String CONFIRM_N = "august-retired-and-inline-mode";
    private static final String CONFIRM_D = "convert-dev-ops-script";
    private static final String CONFIRM_ROLLBACK = "rollback-lob-text";

    private static final String BACKUP = "lob_text_backup_20261005";
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 1, 3, 0);
    private static final int DEV_OPS_ROWS = 40;

    /** psql prefixes a notice with "psql:<file>:<line>: " under -f, so the regex is never anchored. */
    private static final Pattern NOTICE =
            Pattern.compile("NOTICE:\\s+lobtext\\|([^|]*)\\|([^|]*)\\|([^|]*)\\|([^|]*)\\|(.*)");
    private static final Pattern URL =
            Pattern.compile("jdbc:postgresql://([^/:]+)(?::(\\d+))?/(\\w+)");

    /** The four in-scope columns: table, key column, value column. */
    private static final List<String[]> IN_SCOPE = List.of(
            new String[] {"cached_data", "cache_key", "json_data"},
            new String[] {"cloudsitter_policies", "id", "schedule_json"},
            new String[] {"github_integration_config", "id", "encrypted_pat"},
            new String[] {"sonarqube_project", "id", "encrypted_token"});

    /** Script D's columns, with the number of references each one is seeded with. */
    private static final List<Object[]> DEV_OPS_SCOPE = List.of(
            new Object[] {"dev_ops_script", "id", "code", DEV_OPS_ROWS},
            new Object[] {"dev_ops_script", "id", "usage_instructions", DEV_OPS_ROWS},
            new Object[] {"devops_script", "id", "code", 1},
            new Object[] {"devops_script", "id", "usage_instructions", 1});

    private static final java.util.concurrent.atomic.AtomicInteger OWNERS =
            new java.util.concurrent.atomic.AtomicInteger();

    private static String url;
    private static String host;
    private static String port;
    private static Path psql;

    @Autowired
    private CachedDataRepository cachedData;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private PgTestSupport.LoShapes shapes;
    private long ownerId;

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        PgTestSupport.requireLocal();
        url = PgTestSupport.recreateDatabase(DATABASE);
        Matcher m = URL.matcher(url);
        if (!m.matches()) {
            throw new IllegalStateException("unexpected PG test URL");
        }
        host = m.group(1);
        port = m.group(2) == null ? "5432" : m.group(2);
        PgTestSupport.springProperties(url, "create-drop").forEach((k, v) -> registry.add(k, () -> v));
        registry.add("spring.jpa.properties." + LobTextWriteMode.PROPERTY, () -> "legacy");
    }

    @BeforeEach
    void seed() {
        psql = findPsql().orElse(null);
        Assumptions.assumeTrue(psql != null, "psql not found (LOBTEXT_TEST_PSQL or PATH); script test skipped");
        PgTestSupport.execute(url, "DROP TABLE IF EXISTS " + BACKUP);
        PgTestSupport.execute(url, "CREATE TABLE IF NOT EXISTS dev_ops_script ("
                + "id bigint PRIMARY KEY, name varchar(200) NOT NULL, code text NOT NULL, usage_instructions text)");
        PgTestSupport.execute(url, "TRUNCATE TABLE cached_data, cloudsitter_policies, github_integration_config,"
                + " sonarqube_project, devops_script, dev_ops_script CASCADE");
        ownerId = owner();
        shapes = PgTestSupport.createLoShapes(url);
        for (String[] column : IN_SCOPE) {
            seedShapes(column[0], column[2]);
            PgTestSupport.grantTable(url, column[0]);
        }
        seedDevOpsScripts();
        PgTestSupport.grantTable(url, "devops_script");
        PgTestSupport.grantTable(url, "dev_ops_script");
    }

    // ------------------------------------------------------------------ AC-26 to AC-28: Script N

    @Test
    void scriptNConvertsOnlyTheResolvableReferencesAndIsUndoable() {
        Map<String, String> before = digests(IN_SCOPE);

        // 1. no confirmation: nothing runs, and the backup table is not even created.
        Result refused = run(NORMALIZE);
        assertThat(refused.exitCode()).as(refused.stderr()).isNotZero();
        assertThat(refused.stderr()).contains("refusing to run normalize-lob-text-columns.sql");
        assertThat(tableExists(BACKUP)).isFalse();
        assertThat(digests(IN_SCOPE)).isEqualTo(before);

        // 2. the rehearsal hook: everything is converted, then rolled back.
        Result rehearsal = run(NORMALIZE, set("lobtext.confirm", CONFIRM_N), set("lobtext.fail_after_convert", "on"));
        assertThat(rehearsal.exitCode()).as(rehearsal.stderr()).isNotZero();
        assertThat(rehearsal.stderr()).contains("lobtext.fail_after_convert=on");
        for (String[] column : IN_SCOPE) {
            assertThat(metric(rehearsal, "run", column[0], column[2], "converted"))
                    .as("%s.%s converted in the rehearsal", column[0], column[2]).isEqualTo(3);
        }
        assertThat(tableExists(BACKUP)).isFalse();
        assertThat(digests(IN_SCOPE)).isEqualTo(before);
        assertThat(lastUpdated("lobtext::json")).isEqualTo(T0);

        // 3. the real run.
        Result converted = run(NORMALIZE, set("lobtext.confirm", CONFIRM_N));
        assertThat(converted.exitCode()).as(converted.stderr()).isZero();
        for (String[] column : IN_SCOPE) {
            String table = column[0];
            String value = column[2];
            assertThat(metric(converted, "before", table, value, "candidates")).isEqualTo(7);
            assertThat(metric(converted, "before", table, value, "lo_exists")).isEqualTo(6);
            assertThat(metric(converted, "before", table, value, "readable")).isEqualTo(6);
            assertThat(metric(converted, "before", table, value, "decodable")).isEqualTo(4);
            assertThat(metric(converted, "before", table, value, "oid_shaped_content")).isEqualTo(1);
            assertThat(metric(converted, "before", table, value, "convertible")).isEqualTo(3);
            assertThat(metric(converted, "run", table, value, "converted")).isEqualTo(3);
            assertThat(metric(converted, "run", table, value, "skipped_missing")).isEqualTo(1);
            assertThat(metric(converted, "run", table, value, "skipped_unreadable")).isZero();
            assertThat(metric(converted, "run", table, value, "skipped_undecodable")).isEqualTo(2);
            assertThat(metric(converted, "run", table, value, "skipped_oid_shaped")).isEqualTo(1);
            assertThat(metric(converted, "run", table, value, "concurrent_changed")).isZero();
            assertThat(metric(converted, "after", table, value, "convertible")).isZero();

            // the converted rows hold the payload, the skipped ones still hold their reference
            assertThat(stored(table, column[1], value, "valid_ref")).isEqualTo(PgTestSupport.VALID_REF_CONTENT);
            assertThat(stored(table, column[1], value, "non_ascii_ref")).isEqualTo(PgTestSupport.NON_ASCII_CONTENT);
            assertThat(stored(table, column[1], value, "dangling_ref")).isEqualTo(shapes.danglingRef());
            assertThat(stored(table, column[1], value, "invalid_utf8_ref")).isEqualTo(shapes.invalidUtf8Ref());
            assertThat(stored(table, column[1], value, "oid_shaped_content_ref"))
                    .isEqualTo(shapes.oidShapedContentRef());
        }
        assertThat(metric(converted, "run", "all", "all", "converted")).isEqualTo(12);
        assertThat(count("SELECT count(*) FROM " + BACKUP)).isEqualTo(12);
        assertThat(lastUpdated("lobtext::json")).as("last_updated is preserved").isEqualTo(T0);
        assertThat(lastUpdated("lobtext::valid_ref")).isEqualTo(T0);

        // 4. a second run converts nothing.
        Result again = run(NORMALIZE, set("lobtext.confirm", CONFIRM_N));
        assertThat(again.exitCode()).as(again.stderr()).isZero();
        assertThat(metric(again, "run", "all", "all", "converted")).isZero();
        assertThat(count("SELECT count(*) FROM " + BACKUP)).isEqualTo(12);

        // 5. the rollback restores every original, byte for byte.
        Result rolledBack = run(ROLLBACK, set("lobtext.confirm", CONFIRM_ROLLBACK));
        assertThat(rolledBack.exitCode()).as(rolledBack.stderr()).isZero();
        assertThat(metric(rolledBack, "run", "all", "all", "restored")).isEqualTo(12);
        assertThat(metric(rolledBack, "run", "all", "all", "restore_skipped_changed")).isZero();
        assertThat(digests(IN_SCOPE)).isEqualTo(before);
        assertThat(lastUpdated("lobtext::valid_ref")).isEqualTo(T0);
        assertThat(count("SELECT count(*) FROM " + BACKUP)).as("backup rows are kept").isEqualTo(12);

        // a second rollback restores nothing and reports every row as already restored
        Result twice = run(ROLLBACK, set("lobtext.confirm", CONFIRM_ROLLBACK));
        assertThat(twice.exitCode()).as(twice.stderr()).isZero();
        assertThat(metric(twice, "run", "all", "all", "restored")).isZero();
        assertThat(metric(twice, "run", "all", "all", "already_restored")).isEqualTo(12);
        assertThat(digests(IN_SCOPE)).isEqualTo(before);
    }

    @Test
    void scriptNLeavesTheDevOpsLibraryAlone() {
        Map<String, String> before = digests(devOpsColumns());
        Result converted = run(NORMALIZE, set("lobtext.confirm", CONFIRM_N));
        assertThat(converted.exitCode()).as(converted.stderr()).isZero();
        assertThat(digests(devOpsColumns())).isEqualTo(before);
    }

    // ------------------------------------------------------------------ AC-29: Script D

    @Test
    void scriptDConvertsTheDevOpsScriptBodiesAndIsUndoable() {
        Map<String, String> before = digests(devOpsColumns());

        Result refused = run(DEV_OPS);
        assertThat(refused.exitCode()).as(refused.stderr()).isNotZero();
        assertThat(refused.stderr()).contains("refusing to run dev-ops-script-lob-to-inline.sql");
        assertThat(tableExists(BACKUP)).isFalse();
        assertThat(digests(devOpsColumns())).isEqualTo(before);

        Result rehearsal = run(DEV_OPS, set("lobtext.confirm", CONFIRM_D), set("lobtext.fail_after_convert", "on"));
        assertThat(rehearsal.exitCode()).as(rehearsal.stderr()).isNotZero();
        assertThat(rehearsal.stderr()).contains("lobtext.fail_after_convert=on");
        assertThat(tableExists(BACKUP)).isFalse();
        assertThat(digests(devOpsColumns())).isEqualTo(before);

        Result converted = run(DEV_OPS, set("lobtext.confirm", CONFIRM_D));
        assertThat(converted.exitCode()).as(converted.stderr()).isZero();
        assertThat(converted.stderr()).contains("expected 40 large-object references (F12), measured 40");
        long expected = 0;
        for (Object[] column : DEV_OPS_SCOPE) {
            String table = (String) column[0];
            String value = (String) column[2];
            int refs = (Integer) column[3];
            assertThat(metric(converted, "before", table, value, "candidates")).isEqualTo(refs);
            assertThat(metric(converted, "run", table, value, "converted")).isEqualTo(refs);
            assertThat(metric(converted, "after", table, value, "convertible")).isZero();
            expected += refs;
        }
        assertThat(metric(converted, "run", "all", "all", "converted")).isEqualTo(expected);
        assertThat(count("SELECT count(*) FROM " + BACKUP)).isEqualTo(expected);
        assertThat(PgTestSupport.rawText(url, "dev_ops_script", "id", 1, "code"))
                .isEqualTo(PgTestSupport.VALID_REF_CONTENT);

        Result again = run(DEV_OPS, set("lobtext.confirm", CONFIRM_D));
        assertThat(again.exitCode()).as(again.stderr()).isZero();
        assertThat(metric(again, "run", "all", "all", "converted")).isZero();

        Result rolledBack = run(ROLLBACK, set("lobtext.confirm", CONFIRM_ROLLBACK));
        assertThat(rolledBack.exitCode()).as(rolledBack.stderr()).isZero();
        assertThat(metric(rolledBack, "run", "all", "all", "restored")).isEqualTo(expected);
        assertThat(digests(devOpsColumns())).isEqualTo(before);
    }

    // ------------------------------------------------------------------ the census

    @Test
    void theCensusReportsEveryShapeAndNeverWrites() {
        Map<String, String> before = digests(IN_SCOPE);
        long objects = PgTestSupport.loCount(url);

        Result census = run(CENSUS);
        assertThat(census.exitCode()).as(census.stderr()).isZero();
        for (String[] column : IN_SCOPE) {
            String table = column[0];
            String value = column[2];
            boolean nullable = "cached_data".equals(table) || "cloudsitter_policies".equals(table);
            assertThat(metric(census, "census", table, value, "rows")).isEqualTo(nullable ? 16 : 15);
            assertThat(metric(census, "census", table, value, "nulls")).isEqualTo(nullable ? 1 : 0);
            assertThat(metric(census, "census", table, value, "non_candidate")).isEqualTo(3);
            assertThat(metric(census, "census", table, value, "digits_not_candidate")).isEqualTo(5);
            assertThat(metric(census, "census", table, value, "candidates")).isEqualTo(7);
            assertThat(metric(census, "census", table, value, "lo_exists")).isEqualTo(6);
            assertThat(metric(census, "census", table, value, "readable")).isEqualTo(6);
            assertThat(metric(census, "census", table, value, "decodable")).isEqualTo(4);
            assertThat(metric(census, "census", table, value, "oid_shaped_content")).isEqualTo(1);
            assertThat(metric(census, "census", table, value, "convertible")).isEqualTo(3);
        }
        // the D17 report-only columns are reported, never converted
        assertThat(metric(census, "census", "devops_script", "code", "candidates")).isEqualTo(1);
        assertThat(metric(census, "census", "dev_ops_script", "code", "candidates")).isEqualTo(DEV_OPS_ROWS);

        assertThat(digests(IN_SCOPE)).isEqualTo(before);
        assertThat(PgTestSupport.loCount(url)).isEqualTo(objects);
        assertThat(tableExists(BACKUP)).isFalse();
    }

    @Test
    void theCensusReportsThePermissionBranchUnderTheReaderRole() {
        Result census = run(CENSUS, "SET ROLE " + PgTestSupport.READER_ROLE);
        assertThat(census.exitCode()).as(census.stderr()).isZero();
        assertThat(census.stderr()).doesNotContain("ERROR");
        for (String[] column : IN_SCOPE) {
            // the one large object with no grant to the reader role is the §3.1 permission branch
            assertThat(metric(census, "census", column[0], column[2], "lo_exists")).isEqualTo(6);
            assertThat(metric(census, "census", column[0], column[2], "readable")).isEqualTo(5);
            assertThat(metric(census, "census", column[0], column[2], "convertible")).isEqualTo(2);
        }
    }

    @Test
    void theCensusWatermarkFindsOnlyTheReferenceWrittenAfterIt() {
        PgTestSupport.execute(url, "TRUNCATE TABLE cached_data, cloudsitter_policies, github_integration_config,"
                + " sonarqube_project, devops_script, dev_ops_script CASCADE");
        long watermark = PgTestSupport.queryLong(url, "SELECT max(oid)::bigint FROM pg_largeobject_metadata");

        // one legacy-mode write through LegacyLobTextType, which mints a new large object
        cachedData.save(new CachedData("lobtext::after-watermark", "{\"after\":true}", T0));
        String ref = PgTestSupport.rawText(url, "cached_data", "cache_key", "lobtext::after-watermark", "json_data");
        assertThat(Long.parseLong(ref)).isGreaterThan(watermark);

        Result census = run(CENSUS, List.of("-v", "since_oid=" + watermark));
        assertThat(census.exitCode()).as(census.stderr()).isZero();
        assertThat(census.stderr()).contains("watermark since_oid=" + watermark);
        long above = 0;
        for (Notice notice : census.notices()) {
            if ("above_watermark".equals(notice.metric())) {
                above += Long.parseLong(notice.value());
            }
        }
        assertThat(above).as("exactly the reference written after the watermark").isEqualTo(1);
        assertThat(metric(census, "census", "cached_data", "json_data", "above_watermark")).isEqualTo(1);
    }

    @Test
    void theCensusRejectsAWatermarkThatIsNotDigits() {
        Result census = run(CENSUS, List.of("-v", "since_oid=12; DROP TABLE cached_data"));
        assertThat(census.exitCode()).isNotZero();
        assertThat(census.stderr()).contains("since_oid must be digits only");
        assertThat(tableExists("cached_data")).isTrue();
    }

    // ------------------------------------------------------------------ fixtures

    /** Every FR-1.2 shape, as the stored text of one column. NULL only where the column allows it. */
    private Map<String, String> shapeValues(boolean nullable) {
        Map<String, String> values = new LinkedHashMap<>();
        if (nullable) {
            values.put("null", null);
        }
        values.put("json", "{\"json\":1}");
        values.put("zero", "0");
        values.put("zero_decimal", "0.0");
        values.put("below_range", "16383");
        values.put("leading_zero", "016842");
        values.put("above_range", "4294967296");
        values.put("ten_nines", "9999999999");
        values.put("empty", "");
        values.put("valid_ref", shapes.validRef());
        values.put("non_ascii_ref", shapes.nonAsciiRef());
        values.put("dangling_ref", shapes.danglingRef());
        values.put("invalid_utf8_ref", shapes.invalidUtf8Ref());
        values.put("nul_byte_ref", shapes.nulByteRef());
        values.put("oid_shaped_content_ref", shapes.oidShapedContentRef());
        values.put("ungranted_ref", shapes.ungrantedRef());
        return values;
    }

    private void seedShapes(String table, String column) {
        boolean nullable = "cached_data".equals(table) || "cloudsitter_policies".equals(table);
        int id = 0;
        for (Map.Entry<String, String> shape : shapeValues(nullable).entrySet()) {
            id++;
            switch (table) {
                case "cached_data" -> PgTestSupport.execute(url,
                        "INSERT INTO cached_data (cache_key, json_data, last_updated) VALUES (?, ?, ?)",
                        "lobtext::" + shape.getKey(), shape.getValue(), T0);
                case "cloudsitter_policies" -> PgTestSupport.execute(url,
                        "INSERT INTO cloudsitter_policies (id, name, type, notifications_enabled, schedule_json)"
                                + " VALUES (?, ?, ?, ?, ?)",
                        (long) id, shape.getKey(), "SCHEDULE", false, shape.getValue());
                case "github_integration_config" -> PgTestSupport.execute(url,
                        "INSERT INTO github_integration_config (id, user_id, github_owner, github_repo, encrypted_pat)"
                                + " VALUES (?, ?, ?, ?, ?)",
                        (long) id, ownerId, "acme", shape.getKey(), shape.getValue());
                case "sonarqube_project" -> PgTestSupport.execute(url,
                        "INSERT INTO sonarqube_project (id, user_id, project_name, server_url, project_key,"
                                + " encrypted_token) VALUES (?, ?, ?, ?, ?, ?)",
                        (long) id, ownerId, shape.getKey(), "https://sonar.example.invalid",
                        "acme_" + shape.getKey(), shape.getValue());
                default -> throw new IllegalArgumentException(table);
            }
        }
    }

    /** dev_ops_script gets 40 references per column (F12); devops_script gets one row with each. */
    private void seedDevOpsScripts() {
        String body = Long.toString(PgTestSupport.createLoText(url, PgTestSupport.VALID_REF_CONTENT));
        PgTestSupport.grantLo(url, Long.parseLong(body));
        for (int id = 1; id <= DEV_OPS_ROWS; id++) {
            PgTestSupport.execute(url,
                    "INSERT INTO dev_ops_script (id, name, code, usage_instructions) VALUES (?, ?, ?, ?)",
                    (long) id, "script-" + id, body, body);
        }
        String insert = "INSERT INTO devops_script (id, name, category, version, code, usage_instructions,"
                + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        PgTestSupport.execute(url, insert,
                1L, "legacy-ref", "bash", "1.0", shapes.validRef(), shapes.nonAsciiRef(), T0, T0);
        PgTestSupport.execute(url, insert,
                2L, "inline", "bash", "1.0", "echo ok", "run it", T0, T0);
    }

    /** A fresh client and user per test: {@code users.username} and {@code client.name} are unique. */
    private long owner() {
        int sequence = OWNERS.incrementAndGet();
        return new TransactionTemplate(transactionManager).execute(status -> {
            Client client = new Client();
            client.setName("lobtext-scripts-client-" + sequence);
            entityManager.persist(client);
            User user = new User();
            user.setUsername("lobtext-scripts-owner-" + sequence);
            user.setPassword("x");
            user.setRole("XAMOPS");
            user.setClient(client);
            entityManager.persist(user);
            entityManager.flush();
            return user.getId();
        });
    }

    /** The key of a seeded shape row, as text: the cache key for cached_data, the id elsewhere. */
    private static String keyOf(String table, String shape, int id) {
        return "cached_data".equals(table) ? "lobtext::" + shape : Integer.toString(id);
    }

    private String stored(String table, String keyColumn, String valueColumn, String shape) {
        int id = 0;
        boolean nullable = "cached_data".equals(table) || "cloudsitter_policies".equals(table);
        for (String name : shapeValues(nullable).keySet()) {
            id++;
            if (name.equals(shape)) {
                return PgTestSupport.rawText(url, table, keyColumn, keyOf(table, shape, id), valueColumn);
            }
        }
        throw new IllegalArgumentException(shape);
    }

    private static List<String[]> devOpsColumns() {
        List<String[]> columns = new ArrayList<>();
        for (Object[] column : DEV_OPS_SCOPE) {
            columns.add(new String[] {(String) column[0], (String) column[1], (String) column[2]});
        }
        return columns;
    }

    // ------------------------------------------------------------------ inspection helpers

    /** md5 of every (key, value) pair of each column, so any change anywhere shows up. */
    private static Map<String, String> digests(List<String[]> columns) {
        Map<String, String> digests = new LinkedHashMap<>();
        for (String[] column : columns) {
            digests.put(column[0] + "." + column[2], PgTestSupport.queryString(url,
                    "SELECT md5(coalesce(string_agg(" + column[1] + "::text || '=' || coalesce(" + column[2]
                            + ", '<null>'), '|' ORDER BY " + column[1] + "::text), '')) FROM " + column[0]));
        }
        return digests;
    }

    private static LocalDateTime lastUpdated(String key) {
        return LocalDateTime.parse(PgTestSupport.queryString(url,
                "SELECT to_char(last_updated, 'YYYY-MM-DD\"T\"HH24:MI:SS') FROM cached_data WHERE cache_key = ?", key));
    }

    private static boolean tableExists(String table) {
        return PgTestSupport.queryString(url, "SELECT to_regclass(?)::text", table) != null;
    }

    private static long count(String sql) {
        return PgTestSupport.queryLong(url, sql);
    }

    // ------------------------------------------------------------------ psql

    private record Notice(String phase, String table, String column, String metric, String value) {
    }

    private record Result(int exitCode, String stdout, String stderr, List<Notice> notices) {
    }

    private static String set(String setting, String value) {
        return "SET " + setting + " = '" + value + "'";
    }

    private Result run(String script, String... sessionCommands) {
        List<String> extra = new ArrayList<>();
        for (String command : sessionCommands) {
            extra.add("-c");
            extra.add(command);
        }
        return run(script, extra);
    }

    /** One psql session: the -c commands first, then the script, exactly as the runbook does it. */
    private Result run(String script, List<String> extraArguments) {
        List<String> command = new ArrayList<>(List.of(psql.toString(), "-X", "-v", "ON_ERROR_STOP=1",
                "-h", host, "-p", port, "-U", PgTestSupport.user(), "-d", DATABASE));
        command.addAll(extraArguments);
        command.add("-f");
        command.add(SCRIPTS.resolve(script).toString());
        try {
            Process process = new ProcessBuilder(command).start();
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            List<Notice> notices = new ArrayList<>();
            for (String line : stderr.lines().toList()) {
                Matcher m = NOTICE.matcher(line);
                if (m.find()) {
                    notices.add(new Notice(m.group(1), m.group(2), m.group(3), m.group(4), m.group(5).trim()));
                }
            }
            return new Result(exitCode, stdout, stderr, notices);
        } catch (IOException e) {
            throw new UncheckedIOException(String.join(" ", command), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while running psql", e);
        }
    }

    private static long metric(Result result, String phase, String table, String column, String metric) {
        List<String> matches = result.notices().stream()
                .filter(n -> n.phase().equals(phase) && n.table().equals(table)
                        && n.column().equals(column) && n.metric().equals(metric))
                .map(Notice::value).toList();
        assertThat(matches).as("one %s|%s|%s|%s line in:%n%s", phase, table, column, metric, result.stderr())
                .hasSize(1);
        return Long.parseLong(matches.get(0));
    }

    /** {@code LOBTEXT_TEST_PSQL}, else the first psql on PATH. */
    private static Optional<Path> findPsql() {
        String configured = System.getenv("LOBTEXT_TEST_PSQL");
        if (configured != null && !configured.isBlank()) {
            Path candidate = Path.of(configured.trim());
            return Files.isExecutable(candidate) ? Optional.of(candidate) : Optional.empty();
        }
        String path = System.getenv("PATH");
        if (path == null) {
            return Optional.empty();
        }
        for (String entry : path.split(java.io.File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            Path candidate = Path.of(entry).resolve("psql");
            if (Files.isExecutable(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }
}
