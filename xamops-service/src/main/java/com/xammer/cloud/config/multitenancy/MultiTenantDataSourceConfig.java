package com.xammer.cloud.config.multitenancy;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Configuration
public class MultiTenantDataSourceConfig {

    @Value("${spring.datasource.url}")
    private String masterUrl;

    @Value("${spring.datasource.username}")
    private String masterUsername;

    @Value("${spring.datasource.password}")
    private String masterPassword;

    @Value("${spring.datasource.driver-class-name}")
    private String masterDriver;

    // --- Inject Hikari Configurations ---
    @Value("${spring.datasource.hikari.connection-timeout:20000}")
    private long connectionTimeout;

    @Value("${spring.datasource.hikari.idle-timeout:600000}")
    private long idleTimeout;

    @Value("${spring.datasource.hikari.max-lifetime:600000}")
    private long maxLifetime;

    @Value("${spring.datasource.hikari.keepalive-time:300000}")
    private long keepaliveTime;

    @Value("${spring.datasource.hikari.minimum-idle:5}")
    private int minimumIdle;

    @Value("${spring.datasource.hikari.validation-timeout:3000}")
    private long validationTimeout;

    @Value("${spring.datasource.hikari.connection-test-query:SELECT 1}")
    private String connectionTestQuery;

    /**
     * 1. Master Data Source (Direct Access)
     * Used for Global User Lookups and Tenant Config loading.
     */
    @Bean(name = "masterDataSource")
    public DataSource createMasterDataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(masterUrl);
        dataSource.setUsername(masterUsername);
        dataSource.setPassword(masterPassword);
        dataSource.setDriverClassName(masterDriver);
        return dataSource;
    }

    /**
     * 2. Routing Data Source (The "Smart" Router)
     * Used by the main application (JPA/Repositories) to switch databases
     * dynamically.
     */
    @Bean
    @Primary
    public DataSource dataSource() {
        DataSource masterDs = createMasterDataSource();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(masterDs);

        // Load active tenants from the config table
        List<Map<String, Object>> tenantRows = jdbcTemplate
                .queryForList("SELECT * FROM tenant_config WHERE active = true");

        Map<Object, Object> targetDataSources = new HashMap<>();

        for (Map<String, Object> row : tenantRows) {
            String tenantId = (String) row.get("tenant_id");
            String url = (String) row.get("db_url");
            String username = (String) row.get("db_username");
            String password = (String) row.get("db_password");
            String driver = (String) row.get("driver_class_name");

            HikariDataSource tenantDs = new HikariDataSource();
            tenantDs.setJdbcUrl(url);
            tenantDs.setUsername(username);
            tenantDs.setPassword(password);
            tenantDs.setDriverClassName(driver);
            tenantDs.setPoolName("TenantPool-" + tenantId);

            // --- Apply Optimized Settings ---
            tenantDs.setMaximumPoolSize(10);
            tenantDs.setMinimumIdle(minimumIdle);
            tenantDs.setConnectionTimeout(connectionTimeout);
            tenantDs.setIdleTimeout(idleTimeout);
            tenantDs.setMaxLifetime(maxLifetime);
            tenantDs.setKeepaliveTime(keepaliveTime);
            tenantDs.setValidationTimeout(validationTimeout);
            tenantDs.setConnectionTestQuery(connectionTestQuery);

            targetDataSources.put(tenantId, tenantDs);
        }

        TenantRoutingDataSource routingDataSource = new TenantRoutingDataSource();
        routingDataSource.setTargetDataSources(targetDataSources);
        routingDataSource.setDefaultTargetDataSource(masterDs); // Fallback to Master

        routingDataSource.afterPropertiesSet();
        return routingDataSource;
    }
}