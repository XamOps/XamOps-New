package com.xammer.cloud.xampulse.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;

/**
 * Holds the two ClickHouse pools (reader with row policy, writer for DDL and platform writes).
 *
 * <p>Deliberately NOT {@link DataSource} beans: Spring Boot only builds the primary Postgres DataSource when no
 * other DataSource bean exists, so exposing these as beans would silently replace it.
 */
public class ClickHouseConnections implements AutoCloseable {

    private final HikariDataSource reader;
    private final HikariDataSource writer;

    public ClickHouseConnections(XamPulseProperties.ClickHouse props) {
        if (props.getUrl() == null || props.getUrl().isBlank()) {
            throw new IllegalStateException("xampulse.clickhouse.url (XAMPULSE_CLICKHOUSE_URL) is required when xampulse.enabled=true");
        }
        this.reader = pool("xp-ch-reader", props, props.getReaderUser(), props.getReaderPassword(), 8);
        this.writer = pool("xp-ch-writer", props, props.getWriterUser(), props.getWriterPassword(), 4);
    }

    private static HikariDataSource pool(String name, XamPulseProperties.ClickHouse p, String user, String pass, int size) {
        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName(name);
        cfg.setJdbcUrl(p.getUrl());
        cfg.setUsername(user);
        cfg.setPassword(pass);
        cfg.setMaximumPoolSize(size);
        cfg.setMinimumIdle(0);
        cfg.setInitializationFailTimeout(-1); // boot must not fail because ClickHouse is down
        cfg.setConnectionTimeout(5_000);
        return new HikariDataSource(cfg);
    }

    public DataSource reader() { return reader; }

    public DataSource writer() { return writer; }

    @Override
    public void close() {
        reader.close();
        writer.close();
    }
}
