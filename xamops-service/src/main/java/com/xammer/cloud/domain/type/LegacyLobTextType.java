package com.xammer.cloud.domain.type;

import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.type.descriptor.WrapperOptions;
import org.hibernate.type.spi.TypeBootstrapContext;
import org.hibernate.usertype.DynamicParameterizedType;
import org.hibernate.usertype.UserType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Hibernate mapping for TEXT columns that may hold either inline text or a legacy PostgreSQL
 * large-object reference: the decimal OID text that the August images (Hibernate 5, {@code @Lob})
 * wrote into {@code cached_data.json_data}, {@code cloudsitter_policies.schedule_json},
 * {@code github_integration_config.encrypted_pat} and {@code sonarqube_project.encrypted_token}.
 *
 * <p><b>Reads</b> use {@code getString}. Only a strict candidate (ASCII {@code [1-9][0-9]{4,9}} in
 * 16384..4294967295, no trimming) costs one extra statement on the same connection. That statement
 * checks existence and SELECT privilege and reads the bytes under one snapshot, so it cannot raise
 * and never aborts the caller's transaction. The bytes are decoded strictly as UTF-8. A missing,
 * unreadable or undecodable object reads as {@code null}, with one rate-limited WARN that names
 * table, column, row key, OID and reason, never the payload. Everything else, and every value on a
 * non-PostgreSQL dialect, is returned unchanged.
 *
 * <p><b>Writes</b> follow {@link LobTextWriteMode}. {@code legacy} (the default) creates a new large
 * object with {@code lo_from_bytea} on the statement's own connection and transaction, and stores
 * its OID as decimal text, so readers on the old images keep working. It refuses before any SQL on
 * an auto-commit or read-only connection. Earlier objects are never removed. {@code inline} stores
 * the value itself and refuses OID-shaped values. Other dialects (H2) always store the value.
 *
 * <p>The only constructor takes {@link TypeBootstrapContext}. Hibernate uses it only when
 * {@code hibernate.cdi.extensions=true}, which Spring's {@code HibernateJpaVendorAdapter} always
 * sets; a plain Hibernate bootstrap that maps these entities must set it too.
 *
 * <p>This file is byte-identical in both services ({@code LobTextSourceParityTest}).
 */
public final class LegacyLobTextType implements UserType<String>, DynamicParameterizedType {

    /** {@code @Parameter} name: the physical key column ({@code "cache_key"} or {@code "id"}). */
    public static final String KEY_COLUMN = "keyColumn";

    /** ASCII digits only, used with {@code matches()} on the raw value; no trimming. */
    static final Pattern CANDIDATE = Pattern.compile("[1-9][0-9]{4,9}");
    static final long MIN_OID = 16_384L, MAX_OID = 4_294_967_295L;

    /** Existence, privilege and content of one large object, in one snapshot. Never raises. */
    static final String RESOLVE_SQL = """
            SELECT q.lo_exists, q.can_read, CASE WHEN q.can_read THEN lo_get(q.loid) END AS bytes
            FROM (SELECT m.oid AS loid, (m.oid IS NOT NULL) AS lo_exists,
                         CASE WHEN m.oid IS NULL THEN false
                              WHEN (SELECT r.rolsuper FROM pg_roles r WHERE r.rolname = current_user) THEN true
                              WHEN current_setting('lo_compat_privileges')::boolean THEN true
                              WHEN pg_has_role(m.lomowner, 'USAGE') THEN true
                              ELSE EXISTS (SELECT 1 FROM aclexplode(COALESCE(m.lomacl, acldefault('L', m.lomowner))) a
                                           WHERE a.privilege_type = 'SELECT'
                                             AND CASE WHEN a.grantee = 0 THEN true ELSE pg_has_role(a.grantee, 'USAGE') END)
                         END AS can_read
                  FROM (SELECT CAST(? AS bigint) AS v) p
                  LEFT JOIN pg_largeobject_metadata m ON m.oid = CAST(p.v AS oid)) q
            """;

    /** Legacy write: a new large object holding the value's UTF-8 bytes. */
    static final String CREATE_SQL = "SELECT lo_from_bytea(0, ?)";

    private static final Logger log = LoggerFactory.getLogger(LegacyLobTextType.class);

    private static final Pattern IDENT = Pattern.compile("[a-z_][a-z0-9_]*");
    private static final long WARN_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(10);
    private static final int WARN_STATE_LIMIT = 10_000;
    private static final long KEY_LOOKUP_WINDOW_NANOS = TimeUnit.MINUTES.toNanos(1);
    private static final int KEY_LOOKUPS_PER_WINDOW = 20;

    private final LobTextWriteMode mode;
    private String table = "?", column = "?", keyLookupSql;               // set once in setParameterValues

    // Rate-limit state: the only mutable state after bootstrap.
    private final ConcurrentHashMap<Long, Long> lastWarnByOid = new ConcurrentHashMap<>();
    private final Object keyLookupLock = new Object();
    private long keyLookupWindowStart = System.nanoTime();                // guarded by keyLookupLock
    private int keyLookupsInWindow;                                        // guarded by keyLookupLock

    /** The only constructor. Needs {@code hibernate.cdi.extensions=true}, which Spring always sets. */
    public LegacyLobTextType(TypeBootstrapContext ctx) {
        this.mode = LobTextWriteMode.parse(ctx.getConfigurationSettings().get(LobTextWriteMode.PROPERTY));
    }

    /** The write mode this instance was bootstrapped with. */
    public LobTextWriteMode writeMode() {
        return mode;
    }

    /**
     * True when the stored text is a legacy reference candidate: ASCII {@code [1-9][0-9]{4,9}}
     * whose value is in 16384..4294967295.
     */
    public static boolean isCandidate(String s) {
        if (s == null || !CANDIDATE.matcher(s).matches()) {
            return false;
        }
        long v = Long.parseLong(s);
        return v >= MIN_OID && v <= MAX_OID;
    }

    @Override
    public void setParameterValues(Properties parameters) {               // table/column from Hibernate's mapping
        ParameterType pt = (ParameterType) parameters.get(PARAMETER_TYPE);
        if (pt == null) {
            return;                                                       // keep "?" labels, no key lookup
        }
        String[] columns = pt.getColumns();
        table = ident(pt.getTable());
        column = ident(columns == null || columns.length == 0 ? null : columns[0]);
        keyLookupSql = "SELECT " + ident(parameters.getProperty(KEY_COLUMN)) + "::text FROM " + table
                + " WHERE " + column + " = ? LIMIT 1";
    }

    @Override
    public int getSqlType() {
        return Types.VARCHAR;                                             // same JDBC code as a plain String
    }

    @Override
    public Class<String> returnedClass() {
        return String.class;
    }

    @Override
    public boolean isMutable() {
        return false;
    }

    @Override
    public String deepCopy(String value) {
        return value;
    }

    @Override
    public String nullSafeGet(ResultSet rs, int position, WrapperOptions options) throws SQLException {
        String raw = rs.getString(position);
        if (raw == null || !(options.getDialect() instanceof PostgreSQLDialect) || !isCandidate(raw)) {
            return raw;
        }
        return resolve(rs.getStatement().getConnection(), raw);           // same connection and transaction
    }

    @Override
    public void nullSafeSet(PreparedStatement st, String value, int index, WrapperOptions options)
            throws SQLException {
        if (value == null) {
            st.setNull(index, Types.VARCHAR);
            return;
        }
        if (!(options.getDialect() instanceof PostgreSQLDialect)) {
            st.setString(index, value);
            return;
        }
        if (mode == LobTextWriteMode.INLINE) {
            rejectIfCandidate(value);
            st.setString(index, value);
            return;
        }
        Connection connection = st.getConnection();
        if (connection.getAutoCommit() || connection.isReadOnly()) {     // refuse before any SQL
            throw new IllegalStateException(
                    "legacy LO write to " + table + "." + column + " needs a read-write transaction");
        }
        try (PreparedStatement create = connection.prepareStatement(CREATE_SQL)) {
            create.setBytes(1, value.getBytes(StandardCharsets.UTF_8));
            try (ResultSet r = create.executeQuery()) {
                r.next();
                st.setString(index, Long.toString(r.getLong(1)));
            }
        }
    }

    private void rejectIfCandidate(String value) {
        if (isCandidate(value)) {
            log.warn("Refused OID-shaped value table={} column={}", table, column);
            throw new IllegalArgumentException("Refused OID-shaped value table=" + table + " column=" + column);
        }
    }

    private String resolve(Connection connection, String raw) throws SQLException {
        long oid = Long.parseLong(raw);
        boolean exists;
        boolean canRead;
        byte[] bytes;
        try (PreparedStatement ps = connection.prepareStatement(RESOLVE_SQL)) {
            ps.setLong(1, oid);
            try (ResultSet r = ps.executeQuery()) {
                exists = r.next() && r.getBoolean(1);
                canRead = exists && r.getBoolean(2);
                bytes = canRead ? r.getBytes(3) : null;
            }
        }
        if (!exists) {
            return anomaly(connection, raw, oid, "missing");
        }
        if (!canRead) {
            return anomaly(connection, raw, oid, "permission");
        }
        String text = decodeStrict(bytes);
        if (text == null) {
            return anomaly(connection, raw, oid, "invalid-utf8");
        }
        log.debug("Resolved legacy large-object reference table={} column={} oid={} length={}",
                table, column, oid, text.length());
        return text;
    }

    /** Strict UTF-8: malformed or unmappable input, or any NUL byte, gives {@code null}. */
    static String decodeStrict(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        for (byte b : bytes) {
            if (b == 0) {
                return null;
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private String anomaly(Connection connection, String raw, long oid, String reason) throws SQLException {
        if (!shouldWarn(oid)) {
            log.debug("Unreadable legacy large-object reference table={} column={} key={} oid={} reason={}",
                    table, column, "<repeat>", oid, reason);
        } else if (log.isWarnEnabled()) {
            log.warn("Unreadable legacy large-object reference table={} column={} key={} oid={} reason={}",
                    table, column, lookupKey(connection, raw), oid, reason);
        }
        return null;
    }

    /** At most one WARN per OID per 10 minutes; the state is cleared at 10,000 entries. */
    private boolean shouldWarn(long oid) {
        long now = System.nanoTime();
        if (lastWarnByOid.size() >= WARN_STATE_LIMIT) {
            lastWarnByOid.clear();
        }
        boolean[] warn = new boolean[1];
        lastWarnByOid.compute(oid, (k, previous) -> {
            if (previous == null || now - previous >= WARN_INTERVAL_NANOS) {
                warn[0] = true;
                return now;
            }
            return previous;
        });
        return warn[0];
    }

    /** The row key for a WARN: a plain SELECT on the table being read, at most 20 per minute. */
    private String lookupKey(Connection connection, String raw) throws SQLException {
        if (keyLookupSql == null) {
            return "?";
        }
        if (!tryAcquireKeyLookup()) {
            return "<lookup-capped>";
        }
        try (PreparedStatement ps = connection.prepareStatement(keyLookupSql)) {
            ps.setString(1, raw);
            try (ResultSet r = ps.executeQuery()) {
                return r.next() ? r.getString(1) : "?";
            }
        }
    }

    private boolean tryAcquireKeyLookup() {
        synchronized (keyLookupLock) {
            long now = System.nanoTime();
            if (now - keyLookupWindowStart >= KEY_LOOKUP_WINDOW_NANOS) {
                keyLookupWindowStart = now;
                keyLookupsInWindow = 0;
            }
            if (keyLookupsInWindow >= KEY_LOOKUPS_PER_WINDOW) {
                return false;
            }
            keyLookupsInWindow++;
            return true;
        }
    }

    /** {@code [a-z_][a-z0-9_]*} or bootstrap fails, so the key lookup SQL cannot be injected. */
    private static String ident(String name) {
        if (name == null || !IDENT.matcher(name).matches()) {
            throw new IllegalArgumentException("LegacyLobTextType: unsupported SQL identifier '" + name + "'");
        }
        return name;
    }
}
