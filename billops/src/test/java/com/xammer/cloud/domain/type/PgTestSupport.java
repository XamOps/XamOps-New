package com.xammer.cloud.domain.type;

import org.junit.jupiter.api.Assumptions;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Harness for the LOB-text tests that need a throwaway local PostgreSQL (design D16, §7.1).
 *
 * <p>The tests are opt-in: {@value #URL_ENV} names the server, for example
 * {@code jdbc:postgresql://localhost:55432/postgres}, and {@value #USER_ENV} the user (default
 * {@code postgres}, trust authentication). Spring test classes are gated with
 * {@code @EnabledIfEnvironmentVariable(named = "LOBTEXT_TEST_JDBC_URL", matches = ".+")}, so CI
 * reports them as skipped. Every URL must name localhost, 127.0.0.1 or ::1 and carry no parameters;
 * anything else fails the test hard.
 *
 * <p>This file is byte-identical in billops and xamops-service ({@code LobTextSourceParityTest}).
 */
public final class PgTestSupport {

    public static final String URL_ENV = "LOBTEXT_TEST_JDBC_URL";
    public static final String USER_ENV = "LOBTEXT_TEST_USER";

    /** Cluster-wide non-superuser role that the permission tests read through. */
    public static final String READER_ROLE = "lobtext_reader";

    /** In the OID range, and far above anything a throwaway cluster allocates. */
    public static final long DANGLING_OID_BASE = 4_000_000_000L;

    /** Large-object fixture contents. */
    public static final String VALID_REF_CONTENT = "{\"a\":1}";
    public static final String NON_ASCII_CONTENT = "₹ café — 東京";
    public static final byte[] INVALID_UTF8 = {(byte) 0xC3, (byte) 0x28, (byte) 0xFF};
    public static final byte[] NUL_BYTES = {'a', 0, 'b'};

    private static final Pattern LOCAL_URL = Pattern.compile(
            "jdbc:postgresql://(\\[[0-9A-Fa-f:]+\\]|[A-Za-z0-9.-]+)(?::(\\d{1,5}))?/([A-Za-z0-9_]+)");
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "::1");
    private static final Pattern IDENT = Pattern.compile("[a-z_][a-z0-9_]*");
    private static final AtomicLong NEXT_DANGLING = new AtomicLong(DANGLING_OID_BASE);

    private PgTestSupport() {
    }

    // ---------------------------------------------------------------- connection

    /**
     * The validated base URL. Skips (JUnit assumption) when {@value #URL_ENV} is unset, and fails
     * unless the host is local. Call it first in every PG test class.
     */
    public static String requireLocal() {
        String url = System.getenv(URL_ENV);
        Assumptions.assumeTrue(url != null && !url.isBlank(), URL_ENV + " is not set; PostgreSQL test skipped");
        return checkLocal(url.trim());
    }

    /** The test user, {@value #USER_ENV} or {@code postgres}. */
    public static String user() {
        String user = System.getenv(USER_ENV);
        return user == null || user.isBlank() ? "postgres" : user.trim();
    }

    /** The throwaway cluster uses trust authentication. */
    public static String password() {
        return "";
    }

    /** URL of {@code database} on the configured local server. */
    public static String urlFor(String database) {
        Matcher m = LOCAL_URL.matcher(requireLocal());
        if (!m.matches()) {
            fail("unreachable: base URL was validated");
        }
        return "jdbc:postgresql://" + m.group(1) + (m.group(2) == null ? "" : ":" + m.group(2)) + "/" + ident(database);
    }

    /** An auto-commit connection; the URL is re-checked for a local host. */
    public static Connection connect(String url) throws SQLException {
        return DriverManager.getConnection(checkLocal(url), user(), password());
    }

    /** Drops and re-creates {@code database} (one per test class) and returns its URL. */
    public static String recreateDatabase(String database) {
        String name = ident(database);
        try (Connection c = connect(requireLocal()); Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
            s.execute("CREATE DATABASE " + name);
        } catch (SQLException e) {
            throw new IllegalStateException("could not re-create test database " + name, e);
        }
        ensureReaderRole();
        return urlFor(name);
    }

    /** Creates {@value #READER_ROLE} once per cluster; roles are cluster-wide. */
    public static void ensureReaderRole() {
        execute(requireLocal(), "DO $$ BEGIN CREATE ROLE " + READER_ROLE
                + " LOGIN NOSUPERUSER; EXCEPTION WHEN duplicate_object THEN NULL; END $$");
    }

    /**
     * The §7.1 Spring settings for a PG test on {@code url}. Callers add the write mode, and
     * billops classes also set {@code @ActiveProfiles("lobtext-pg")}.
     */
    public static Map<String, String> springProperties(String url, String ddlAuto) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("spring.datasource.url", checkLocal(url));
        p.put("spring.datasource.username", user());
        p.put("spring.datasource.password", password());
        p.put("spring.datasource.hikari.maximum-pool-size", "4");
        p.put("spring.datasource.hikari.minimum-idle", "0");
        p.put("spring.jpa.hibernate.ddl-auto", ddlAuto);
        p.put("spring.flyway.enabled", "false");
        p.put("spring.autoconfigure.exclude", "");
        p.put("eureka.client.enabled", "false");
        return p;
    }

    // ---------------------------------------------------------------- SQL helpers

    /** Runs one statement in auto-commit; parameters bind with {@code setObject}. */
    public static int execute(String url, String sql, Object... params) {
        try (Connection c = connect(url); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            return ps.execute() ? 0 : ps.getUpdateCount();
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    /** First column of the first row, or {@code null} when there is no row. */
    public static String queryString(String url, String sql, Object... params) {
        try (Connection c = connect(url); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    public static long queryLong(String url, String sql, Object... params) {
        String value = queryString(url, sql, params);
        if (value == null) {
            throw new IllegalStateException("no row: " + sql);
        }
        return Long.parseLong(value);
    }

    /** The stored text of one column, read as plain TEXT (no resolution). */
    public static String rawText(String url, String table, String keyColumn, Object key, String valueColumn) {
        return queryString(url, "SELECT " + ident(valueColumn) + " FROM " + ident(table)
                + " WHERE " + ident(keyColumn) + "::text = ?", String.valueOf(key));
    }

    public static boolean rowExists(String url, String table, String keyColumn, Object key) {
        return queryString(url, "SELECT 1 FROM " + ident(table) + " WHERE " + ident(keyColumn) + "::text = ?",
                String.valueOf(key)) != null;
    }

    /** Overwrites the stored text of one column, bypassing Hibernate. */
    public static void setRaw(String url, String table, String keyColumn, Object key, String valueColumn, String raw) {
        int rows = execute(url, "UPDATE " + ident(table) + " SET " + ident(valueColumn) + " = ? WHERE "
                + ident(keyColumn) + "::text = ?", raw, String.valueOf(key));
        if (rows != 1) {
            throw new IllegalStateException("expected one row in " + table + " for key " + key + ", got " + rows);
        }
    }

    public static void grantTable(String url, String table) {
        execute(url, "GRANT SELECT ON " + ident(table) + " TO " + READER_ROLE);
    }

    // ---------------------------------------------------------------- large objects

    /** A new large object holding {@code bytes}, created the way the legacy write path does. */
    public static long createLo(String url, byte[] bytes) {
        try (Connection c = connect(url); PreparedStatement ps = c.prepareStatement("SELECT lo_from_bytea(0, ?)")) {
            ps.setBytes(1, bytes);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("lo_from_bytea", e);
        }
    }

    public static long createLoText(String url, String text) {
        return createLo(url, text.getBytes(StandardCharsets.UTF_8));
    }

    public static void grantLo(String url, long oid) {
        execute(url, "GRANT SELECT ON LARGE OBJECT " + oid + " TO " + READER_ROLE);
    }

    public static boolean loExists(String url, long oid) {
        return queryString(url, "SELECT 1 FROM pg_largeobject_metadata WHERE oid = CAST(? AS bigint)::oid", oid) != null;
    }

    public static long loCount(String url) {
        return queryLong(url, "SELECT count(*) FROM pg_largeobject_metadata");
    }

    /** {@code convert_from(lo_get(oid), 'UTF8')}: the server-side check that ops SQL uses. */
    public static String loText(String url, long oid) {
        return queryString(url, "SELECT convert_from(lo_get(CAST(? AS bigint)::oid), 'UTF8')", oid);
    }

    /** An in-range OID that no large object has; unique within this JVM. */
    public static long danglingOid(String url) {
        long oid = NEXT_DANGLING.incrementAndGet();
        if (loExists(url, oid)) {
            fail("dangling OID fixture " + oid + " unexpectedly exists");
        }
        return oid;
    }

    /**
     * The August read path: pgjdbc {@code getClob} on the TEXT column, with auto-commit off
     * (pgjdbc 42.7.13 uses the same large-object client API as 42.3.7).
     */
    public static String clobText(String url, String table, String keyColumn, Object key, String valueColumn) {
        String sql = "SELECT " + ident(valueColumn) + " FROM " + ident(table) + " WHERE " + ident(keyColumn) + "::text = ?";
        try (Connection c = connect(url)) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, String.valueOf(key));
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalStateException("no row in " + table + " for key " + key);
                    }
                    Clob clob = rs.getClob(1);
                    if (clob == null) {
                        return null;
                    }
                    StringBuilder text = new StringBuilder();
                    try (Reader in = clob.getCharacterStream()) {   // as Hibernate 5 read a @Lob String
                        char[] buffer = new char[4096];
                        for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
                            text.append(buffer, 0, n);
                        }
                    }
                    return text.toString();
                }
            } finally {
                c.rollback();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------------------------------------------------------------- fixtures

    /**
     * The large-object shapes of FR-1.2, as stored column texts. Every object except
     * {@code ungrantedRef} is granted to {@value #READER_ROLE}.
     *
     * @param validRef            an object holding {@value #VALID_REF_CONTENT}
     * @param nonAsciiRef         an object holding {@link #NON_ASCII_CONTENT}
     * @param danglingRef         an in-range OID with no object
     * @param invalidUtf8Ref      an object holding malformed UTF-8
     * @param nulByteRef          an object holding a NUL byte
     * @param oidShapedContentRef an object whose content is {@code validRef}'s OID text
     * @param ungrantedRef        a readable-by-owner object with no grant to the reader role
     */
    public record LoShapes(String validRef, String nonAsciiRef, String danglingRef, String invalidUtf8Ref,
                           String nulByteRef, String oidShapedContentRef, String ungrantedRef) {
    }

    public static LoShapes createLoShapes(String url) {
        long valid = createLoText(url, VALID_REF_CONTENT);
        long nonAscii = createLoText(url, NON_ASCII_CONTENT);
        long invalid = createLo(url, INVALID_UTF8);
        long nul = createLo(url, NUL_BYTES);
        long oidShaped = createLoText(url, Long.toString(valid));
        long ungranted = createLoText(url, "{\"ungranted\":true}");
        for (long oid : new long[] {valid, nonAscii, invalid, nul, oidShaped}) {
            grantLo(url, oid);
        }
        return new LoShapes(Long.toString(valid), Long.toString(nonAscii), Long.toString(danglingOid(url)),
                Long.toString(invalid), Long.toString(nul), Long.toString(oidShaped), Long.toString(ungranted));
    }

    /** One row of {@code lobtext/candidate-vectors.csv}. */
    public record CandidateVector(String value, boolean candidate) {
    }

    /** The shared candidate vectors ({@code value,isCandidate}; RFC 4180 quoting). */
    public static List<CandidateVector> candidateVectors() {
        try (InputStream in = PgTestSupport.class.getResourceAsStream("/lobtext/candidate-vectors.csv")) {
            if (in == null) {
                throw new IllegalStateException("lobtext/candidate-vectors.csv not on the test classpath");
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            List<CandidateVector> vectors = new ArrayList<>();
            String header = reader.readLine();
            if (!"value,isCandidate".equals(header)) {
                throw new IllegalStateException("unexpected header: " + header);
            }
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (line.isEmpty()) {
                    continue;
                }
                int comma = line.lastIndexOf(',');
                String value = line.substring(0, comma);
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1).replace("\"\"", "\"");
                }
                vectors.add(new CandidateVector(value, Boolean.parseBoolean(line.substring(comma + 1).trim())));
            }
            return vectors;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------------------------------------------------------------- internals

    private static String checkLocal(String url) {
        Matcher m = LOCAL_URL.matcher(url);
        if (!m.matches()) {
            fail("PG test URL must look like jdbc:postgresql://localhost:<port>/<database>, without parameters");
        }
        String host = m.group(1);
        if (host.startsWith("[")) {
            host = host.substring(1, host.length() - 1);
        }
        if (!LOCAL_HOSTS.contains(host.toLowerCase(Locale.ROOT))) {
            fail("PG tests only run against localhost, 127.0.0.1 or ::1, not '" + host + "'");
        }
        return url;
    }

    private static String ident(String name) {
        if (name == null || !IDENT.matcher(name).matches()) {
            throw new IllegalArgumentException("unsupported SQL identifier '" + name + "'");
        }
        return name;
    }

    private static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }
}
