package com.xammer.cloud.xampulse.service.platform;

import com.xammer.cloud.xampulse.config.ClickHouseConnections;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parameterised batch INSERT over the writer pool. Requires the writer account to hold INSERT on the target table
 * (see ClickHouse V006/V008 grants).
 *
 * <p>The table name and every column name are validated against a closed per-table whitelist before any SQL is
 * built, so no identifier can ever come from user text. Rows are bound as parameters and flushed in batches of
 * {@link #CHUNK} to keep statement memory bounded.
 */
public class JdbcClickHouseWrites implements ClickHouseWrites {

    static final int CHUNK = 10_000;

    /** The only tables this seam may write, each with its exact, ordered column set. */
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "xp_baseline", Set.of("tenant_id", "metric", "instance_id", "hour_of_week", "med", "mad", "updated_at"),
            "xp_events", Set.of("tenant_id", "ts", "kind", "resource", "title", "detail"),
            "xp_cloudwatch_points", Set.of("tenant_id", "instance_id", "metric", "dimension", "ts", "value", "fetched_at"));

    private final ClickHouseConnections connections;

    public JdbcClickHouseWrites(ClickHouseConnections connections) {
        this.connections = connections;
    }

    @Override
    public void insert(String table, List<String> columns, List<List<Object>> rows) throws SQLException {
        Set<String> allowedCols = ALLOWED.get(table);
        if (allowedCols == null) {
            throw new IllegalArgumentException("Table not permitted for writes: " + table);
        }
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("At least one column is required");
        }
        for (String col : columns) {
            if (!allowedCols.contains(col)) {
                throw new IllegalArgumentException("Column not permitted for " + table + ": " + col);
            }
        }
        if (rows == null || rows.isEmpty()) {
            return;
        }
        for (List<Object> row : rows) {
            if (row.size() != columns.size()) {
                throw new IllegalArgumentException("Row has " + row.size() + " values, expected " + columns.size());
            }
        }
        String sql = "INSERT INTO " + table + " (" + String.join(", ", columns) + ") VALUES ("
                + placeholders(columns.size()) + ")";
        try (Connection c = connections.writer().getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            int pending = 0;
            for (List<Object> row : rows) {
                for (int i = 0; i < row.size(); i++) {
                    ps.setObject(i + 1, utcIfInstant(row.get(i)));
                }
                ps.addBatch();
                if (++pending == CHUNK) {
                    ps.executeBatch();
                    pending = 0;
                }
            }
            if (pending > 0) {
                ps.executeBatch();
            }
        }
    }

    /**
     * Every XamPulse time column is {@code DateTime64(.., 'UTC')}. clickhouse-jdbc 0.11.0 reads a
     * {@link java.sql.Timestamp} (and a {@link java.time.LocalDateTime}) in the JVM's default zone, so on a backend
     * running in IST an event written "now" was stored 5h30 in the future and the reader's time window missed it.
     * An {@link Instant} is written exactly in any zone (checked against the driver for Timestamp, LocalDateTime,
     * OffsetDateTime, ZonedDateTime and Instant under Asia/Kolkata and UTC), so timestamps are bound as Instants.
     */
    static Object utcIfInstant(Object v) {
        return v instanceof java.sql.Timestamp t ? t.toInstant() : v;
    }

    private static String placeholders(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(i == 0 ? "?" : ", ?");
        }
        return sb.toString();
    }
}
