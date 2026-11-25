package com.xammer.cloud.xampulse.service.platform;

import com.xammer.cloud.xampulse.config.ClickHouseConnections;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/** Mutations over the writer pool. Requires the writer account to hold ALTER on the XamPulse tables. */
public class JdbcClickHouseMutations implements ClickHouseMutations {

    private final ClickHouseConnections connections;

    public JdbcClickHouseMutations(ClickHouseConnections connections) {
        this.connections = connections;
    }

    @Override
    public void execute(String sql, List<Object> params) throws SQLException {
        try (Connection c = connections.writer().getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            ps.executeUpdate();
        }
    }
}
