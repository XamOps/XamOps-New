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

import javax.sql.DataSource;

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
     * These pools are built by hand rather than by Spring Boot autoconfiguration, so
     * spring.datasource.hikari.* is only honoured for the properties explicitly applied
     * below. leak-detection-threshold was set in application-prod.properties but never
     * read, which meant connections leaked by long external HTTP calls were invisible.
     * 0 disables detection; prod sets 30000.
     */
    @Value("${spring.datasource.hikari.leak-detection-threshold:0}")
    private long leakDetectionThreshold;

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
        ds.setLeakDetectionThreshold(leakDetectionThreshold);
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
        ds.setLeakDetectionThreshold(leakDetectionThreshold);
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
    public DataSource prodDataSource() {
        logger.info("========================================");
        logger.info("PROFILE: PROD - Single-Tenant Mode (direct datasource)");
        logger.info("DB URL: {}", dataSourceUrl);
        logger.info("========================================");
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(dataSourceUrl);
        ds.setUsername(dataSourceUsername);
        ds.setPassword(dataSourcePassword);
        ds.setDriverClassName(dataSourceDriver);
        applyHikariSettings(ds, "ProdPool-main");
        return ds;
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "spring.profiles.active", havingValue = "preprod")
    public DataSource preprodDataSource() {
        logger.info("========================================");
        logger.info("PROFILE: PREPROD - Single-Tenant Mode (direct datasource)");
        logger.info("DB URL: {}", dataSourceUrl);
        logger.info("========================================");
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(dataSourceUrl);
        ds.setUsername(dataSourceUsername);
        ds.setPassword(dataSourcePassword);
        ds.setDriverClassName(dataSourceDriver);
        applyHikariSettings(ds, "PreprodPool-main");
        return ds;
    }

    /**
     * Master DataSource Bean for PROD Profile
     * Points to the same single DB as the primary datasource (no separate master DB).
     */
    @Bean(name = "masterDataSource")
    @ConditionalOnProperty(name = "spring.profiles.active", havingValue = "prod")
    public DataSource prodMasterDataSource() {
        logger.info("Creating masterDataSource bean pointing to prod DB (same as primary)");
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(dataSourceUrl);
        ds.setUsername(dataSourceUsername);
        ds.setPassword(dataSourcePassword);
        ds.setDriverClassName(dataSourceDriver);
        applyMasterHikariSettings(ds, "MasterPool-Prod");
        return ds;
    }

    /**
     * Master DataSource Bean for PREPROD Profile
     * Points to the same single DB as the primary datasource (no separate master DB).
     */
    @Bean(name = "masterDataSource")
    @ConditionalOnProperty(name = "spring.profiles.active", havingValue = "preprod")
    public DataSource preprodMasterDataSource() {
        logger.info("Creating masterDataSource bean pointing to preprod DB (same as primary)");
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(dataSourceUrl);
        ds.setUsername(dataSourceUsername);
        ds.setPassword(dataSourcePassword);
        ds.setDriverClassName(dataSourceDriver);
        applyMasterHikariSettings(ds, "MasterPool-Preprod");
        return ds;
    }
}
