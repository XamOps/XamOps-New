package com.xammer.cloud.xampulse.service.query;

import com.xammer.cloud.xampulse.config.ClickHouseConnections;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JDBC implementation over the reader pool (row policy, readonly profile). */
public class JdbcClickHouseGateway implements ClickHouseGateway {

    private final ClickHouseConnections connections;

    public JdbcClickHouseGateway(ClickHouseConnections connections) {
        this.connections = connections;
    }

    @Override
    public List<Map<String, Object>> query(String sql, List<Object> params, int timeoutSec) throws SQLException {
        try (Connection c = connections.reader().getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(timeoutSec);
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                int n = md.getColumnCount();
                List<Map<String, Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= n; i++) {
                        row.put(md.getColumnLabel(i), rs.getObject(i));
                    }
                    rows.add(row);
                }
                return rows;
            }
        }
    }
}
