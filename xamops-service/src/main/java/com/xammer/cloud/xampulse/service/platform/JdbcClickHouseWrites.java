package com.xammer.cloud.xampulse.service.platform;

import com.xammer.cloud.xampulse.config.ClickHouseConnections;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
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
            "xp_events", Set.of("tenant_id", "ts", "kind", "resource", "title", "detail"));

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
                    ps.setObject(i + 1, row.get(i));
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

    private static String placeholders(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(i == 0 ? "?" : ", ?");
        }
        return sb.toString();
    }
}
