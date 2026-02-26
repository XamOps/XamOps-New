package com.xammer.cloud.config.multitenancy;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Configuration
public class MultiTenantDataSourceConfig {

    private static final Logger logger = LoggerFactory.getLogger(MultiTenantDataSourceConfig.class);

    @Value("${spring.datasource.url}")
    private String dataSourceUrl;

    @Value("${spring.datasource.username}")
    private String dataSourceUsername;

    @Value("${spring.datasource.password}")
    private String dataSourcePassword;

    @Value("${spring.datasource.driver-class-name}")
    private String dataSourceDriver;

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

    @Value("${spring.datasource.hikari.maximum-pool-size:10}")
    private int maximumPoolSize;

    @Value("${spring.datasource.hikari.master-maximum-pool-size:10}")
    private int masterMaximumPoolSize;

    @Value("${spring.datasource.hikari.validation-timeout:3000}")
    private long validationTimeout;

    @Value("${spring.datasource.hikari.connection-test-query:SELECT 1}")
    private String connectionTestQuery;

    /**
     * Helper method to create a HikariCP-pooled DataSource for master DB access.
     * Used in PROD/PREPROD for querying tenant_config and global_users tables.
     * Uses connection pooling to prevent connection exhaustion on PostgreSQL.
     */
    private HikariDataSource createMasterDataSourceForLookup() {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl("jdbc:postgresql://3.110.102.128:5432/production_db");
        ds.setUsername("app_user");
        ds.setPassword("AppPassword123");
        ds.setDriverClassName("org.postgresql.Driver");
        applyMasterHikariSettings(ds, "MasterPool-Lookup");
        return ds;
    }

    /**
     * Hikari settings for master datasource (smaller pool - only used for global_users/tenant_config lookups)
     */
    private void applyMasterHikariSettings(HikariDataSource ds, String poolName) {
        ds.setPoolName(poolName);
        ds.setMaximumPoolSize(masterMaximumPoolSize);
        ds.setMinimumIdle(masterMaximumPoolSize / 2);
        ds.setConnectionTimeout(connectionTimeout);
        ds.setIdleTimeout(idleTimeout);
        ds.setMaxLifetime(maxLifetime);
        ds.setKeepaliveTime(keepaliveTime);
        ds.setValidationTimeout(validationTimeout);
        ds.setConnectionTestQuery(connectionTestQuery);
    }

    /**
     * Helper method to apply Hikari settings to a datasource
     */
    private void applyHikariSettings(HikariDataSource ds, String poolName) {
        ds.setPoolName(poolName);
        ds.setMaximumPoolSize(maximumPoolSize);
        ds.setMinimumIdle(minimumIdle);
        ds.setConnectionTimeout(connectionTimeout);
        ds.setIdleTimeout(idleTimeout);
        ds.setMaxLifetime(maxLifetime);
        ds.setKeepaliveTime(keepaliveTime);
        ds.setValidationTimeout(validationTimeout);
        ds.setConnectionTestQuery(connectionTestQuery);
    }

    /**
     * ========================================================================
     * LOCAL PROFILE: Simple Single-Database DataSource
     * ========================================================================
     * Used in local development environment.
     * Connects to: jdbc:postgresql://13.204.166.9:5432/xamops_local_db
     * No multi-tenancy, no routing.
     */
    @Bean
    @Primary
    @ConditionalOnProperty(name = "spring.profiles.active", havingValue = "local")
    public DataSource localDataSource() {
        logger.info("========================================");
        logger.info("PROFILE: LOCAL - Single Database Mode");
        logger.info("========================================");
        logger.info("Database URL: {}", dataSourceUrl);

        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(dataSourceUrl);
        ds.setUsername(dataSourceUsername);
        ds.setPassword(dataSourcePassword);
        ds.setDriverClassName(dataSourceDriver);

        applyHikariSettings(ds, "LocalPool");

        logger.info("Local datasource initialized successfully (No Multi-Tenancy)");
        return ds;
    }

    /**
     * Master DataSource for Local Profile
     * Points to the same local DB - used for global_users table lookups
     */
    @Bean(name = "masterDataSource")
    @ConditionalOnProperty(name = "spring.profiles.active", havingValue = "local")
    public DataSource localMasterDataSource() {
        logger.info("Creating masterDataSource bean pointing to local DB for global_users lookups");
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(dataSourceUrl);
        ds.setUsername(dataSourceUsername);
        ds.setPassword(dataSourcePassword);
        ds.setDriverClassName(dataSourceDriver);
        applyMasterHikariSettings(ds, "MasterPool-Local");
        return ds;
    }

    /**
     * ========================================================================
     * UAT PROFILE: Simple Single-Database DataSource
     * ========================================================================
     * Used in UAT environment.
     * Connects to: jdbc:postgresql://40.192.7.127:5432/xamops_db_uat
     * No multi-tenancy, no routing.
     */
    @Bean
    @Primary
    @ConditionalOnProperty(name = "spring.profiles.active", havingValue = "uat")
    public DataSource uatDataSource() {
        logger.info("========================================");
        logger.info("PROFILE: UAT - Single Database Mode");
        logger.info("========================================");
        logger.info("Database URL: {}", dataSourceUrl);

        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(dataSourceUrl);
        ds.setUsername(dataSourceUsername);
        ds.setPassword(dataSourcePassword);
        ds.setDriverClassName(dataSourceDriver);

        applyHikariSettings(ds, "UATPool");

        logger.info("UAT datasource initialized successfully (No Multi-Tenancy)");
        return ds;
    }

    /**
     * Master DataSource for UAT Profile
     * Points to the same UAT DB - used for global_users table lookups
     */
    @Bean(name = "masterDataSource")
    @ConditionalOnProperty(name = "spring.profiles.active", havingValue = "uat")
    public DataSource uatMasterDataSource() {
        logger.info("Creating masterDataSource bean pointing to UAT DB for global_users lookups");
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(dataSourceUrl);
        ds.setUsername(dataSourceUsername);
        ds.setPassword(dataSourcePassword);
        ds.setDriverClassName(dataSourceDriver);
        applyMasterHikariSettings(ds, "MasterPool-UAT");
        return ds;
    }

    /**
     * ========================================================================
     * PRODUCTION & PREPROD PROFILES: Multi-Tenant Routing DataSource
     * ========================================================================
     * Used in PRODUCTION and PREPROD environments.
     * Reads tenant_config from master DB (production_db) and creates:
     * - Connection pool for System DB (tenant_id = "system" or "default")
     * - Connection pools for Customer DBs (SISL, etc.)
     * 
     * Uses TenantRoutingDataSource to dynamically switch between tenants
     * based on TenantContext.
     */
    @Bean
    @Primary
    @ConditionalOnProperty(name = "spring.profiles.active", havingValue = "prod", matchIfMissing = false)
    public DataSource prodRoutingDataSource() {
        return createRoutingDataSource("PROD");
    }

    // Explicitly define for 'preprod' to ensure it also uses this logic
    @Bean
    @Primary
    @ConditionalOnProperty(name = "spring.profiles.active", havingValue = "preprod")
    public DataSource preprodRoutingDataSource() {
        return createRoutingDataSource("PREPROD");
    }

    private DataSource createRoutingDataSource(String profileName) {
        logger.info("========================================");
        logger.info("PROFILE: {} - Multi-Tenant Routing Mode", profileName);
        logger.info("========================================");
        logger.info("Master DB URL: {}", dataSourceUrl);

        // Create a datasource to query tenant_config table
        DataSource masterDs = createMasterDataSourceForLookup();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(masterDs);

        // Load ALL active tenants (no filtering - master DB is clean now)
        logger.info("Loading tenant configurations from master DB...");
        List<Map<String, Object>> tenantRows = jdbcTemplate
                .queryForList("SELECT * FROM tenant_config WHERE active = true");

        if (tenantRows.isEmpty()) {
            logger.warn("WARNING: No active tenants found in tenant_config table!");
            logger.warn("Please ensure master DB has entries for system and customer tenants");
        }

        Map<Object, Object> targetDataSources = new HashMap<>();
        String defaultTenantId = null;

        for (Map<String, Object> row : tenantRows) {
            String tenantId = (String) row.get("tenant_id");
            String url = (String) row.get("db_url");
            String username = (String) row.get("db_username");
            String password = (String) row.get("db_password");
            String driver = (String) row.get("driver_class_name");

            logger.info("Initializing datasource for tenant: {} ({})", tenantId, url);

            HikariDataSource tenantDs = new HikariDataSource();
            tenantDs.setJdbcUrl(url);
            tenantDs.setUsername(username);
            tenantDs.setPassword(password);
            tenantDs.setDriverClassName(driver);

            applyHikariSettings(tenantDs, "TenantPool-" + tenantId);

            targetDataSources.put(tenantId, tenantDs);

            // Set default tenant (prefer "system", fallback to "default", or first entry)
            if (defaultTenantId == null || "system".equalsIgnoreCase(tenantId) ||
                    "default".equalsIgnoreCase(tenantId)) {
                defaultTenantId = tenantId;
            }
        }

        logger.info("Initialized {} tenant datasources", targetDataSources.size());
        logger.info("Tenant IDs: {}", targetDataSources.keySet());
        logger.info("Default tenant: {}", defaultTenantId);

        TenantRoutingDataSource routingDataSource = new TenantRoutingDataSource();
        routingDataSource.setTargetDataSources(targetDataSources);

        if (defaultTenantId != null && targetDataSources.containsKey(defaultTenantId)) {
            routingDataSource.setDefaultTargetDataSource(targetDataSources.get(defaultTenantId));
            logger.info("Default datasource set to tenant: {}", defaultTenantId);
        } else {
            logger.error("ERROR: Could not set default datasource - no tenants found!");
        }

        routingDataSource.afterPropertiesSet();
        logger.info("Multi-tenant routing datasource initialized successfully");
        logger.info("========================================");

        return routingDataSource;
    }

    /**
     * Master DataSource Bean for PROD & PREPROD Profiles
     * (exposed for services that need to query master DB directly)
     * Points to production_db for tenant_config and global_users lookups
     */
    @Bean(name = "masterDataSource")
    @ConditionalOnProperty(name = "spring.profiles.active", havingValue = "prod")
    public DataSource prodMasterDataSource() {
        logger.info("Creating masterDataSource bean pointing to production_db for global_users lookups");
        return createMasterDataSourceForLookup();
    }

    @Bean(name = "masterDataSource")
    @ConditionalOnProperty(name = "spring.profiles.active", havingValue = "preprod")
    public DataSource preprodMasterDataSource() {
        logger.info("Creating masterDataSource bean pointing to production_db for global_users lookups (PREPROD)");
        return createMasterDataSourceForLookup();
    }
}
