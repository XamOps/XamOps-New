package com.xammer.cloud.xampulse.service.platform;

import com.xammer.cloud.xampulse.config.ClickHouseConnections;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

/** DDL over the writer pool. The only SQL here is static or bound; versions and checksums are parameters. */
public class JdbcClickHouseDdl implements ClickHouseDdl {

    private final ClickHouseConnections connections;

    public JdbcClickHouseDdl(ClickHouseConnections connections) {
        this.connections = connections;
    }

    @Override
    public void execute(String sql) throws SQLException {
        try (Connection c = connections.writer().getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    @Override
    public Map<String, String> applied() throws SQLException {
        Map<String, String> out = new HashMap<>();
        try (Connection c = connections.writer().getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT version, checksum FROM xp_schema_version")) {
            while (rs.next()) {
                out.put(rs.getString(1), rs.getString(2));
            }
        }
        return out;
    }

    @Override
    public void markApplied(String version, String checksum) throws SQLException {
        try (Connection c = connections.writer().getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO xp_schema_version (version, checksum) VALUES (?, ?)")) {
            ps.setString(1, version);
            ps.setString(2, checksum);
            ps.executeUpdate();
        }
    }
}
