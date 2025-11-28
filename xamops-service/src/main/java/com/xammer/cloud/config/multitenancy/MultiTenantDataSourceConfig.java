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

    /**
     * 1. Master Data Source (Direct Access)
     * Used for Global User Lookups and Tenant Config loading.
     * We expose this as a Bean so we can inject it into MasterDatabaseService.
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
     * Used by the main application (JPA/Repositories) to switch databases dynamically.
     */
    @Bean
    @Primary
    public DataSource dataSource() {
        DataSource masterDs = createMasterDataSource();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(masterDs);

        // Load active tenants from the config table
        List<Map<String, Object>> tenantRows = jdbcTemplate.queryForList("SELECT * FROM tenant_config WHERE active = true");

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
            tenantDs.setMaximumPoolSize(10);
            tenantDs.setMinimumIdle(2);

            targetDataSources.put(tenantId, tenantDs);
        }

        TenantRoutingDataSource routingDataSource = new TenantRoutingDataSource();
        routingDataSource.setTargetDataSources(targetDataSources);
        routingDataSource.setDefaultTargetDataSource(masterDs); // Fallback to Master
        
        routingDataSource.afterPropertiesSet();
        return routingDataSource;
    }
}