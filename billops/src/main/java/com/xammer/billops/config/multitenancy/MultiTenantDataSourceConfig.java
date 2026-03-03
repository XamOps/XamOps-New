package com.xammer.billops.config.multitenancy;

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
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

@Configuration
public class MultiTenantDataSourceConfig {

    private static final Logger logger = LoggerFactory.getLogger(MultiTenantDataSourceConfig.class);

    @Autowired
    private Environment env;

    @Value("${spring.datasource.url}")
    private String dataSourceUrl;

    @Value("${spring.datasource.username}")
    private String dataSourceUsername;

    @Value("${spring.datasource.password}")
    private String dataSourcePassword;

    @Value("${spring.datasource.driver-class-name}")
    private String dataSourceDriver;

    // --- Inject Hikari Configurations ---
    @Value("${spring.datasource.hikari.connection-timeout:30000}")
    private long connectionTimeout;

    @Value("${spring.datasource.hikari.idle-timeout:600000}")
    private long idleTimeout;

    @Value("${spring.datasource.hikari.max-lifetime:600000}")
    private long maxLifetime;

    @Value("${spring.datasource.hikari.keepalive-time:200000}")
    private long keepaliveTime;

    @Value("${spring.datasource.hikari.minimum-idle:5}")
    private int minimumIdle;

    @Value("${spring.datasource.hikari.maximum-pool-size:10}")
    private int maximumPoolSize;

    @Value("${spring.datasource.hikari.connection-test-query:SELECT 1}")
    private String connectionTestQuery;

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
        logger.info("BILLOPS - PROFILE: LOCAL - Single Database Mode");
        logger.info("========================================");
        logger.info("Database URL: {}", dataSourceUrl);

        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(dataSourceUrl);
        ds.setUsername(dataSourceUsername);
        ds.setPassword(dataSourcePassword);
        ds.setDriverClassName(dataSourceDriver);

        applyHikariSettings(ds, "BillOps-LocalPool");

        logger.info("BillOps local datasource initialized successfully (No Multi-Tenancy)");
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
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(dataSourceUrl);
        dataSource.setUsername(dataSourceUsername);
        dataSource.setPassword(dataSourcePassword);
        dataSource.setDriverClassName(dataSourceDriver);
        return dataSource;
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
        logger.info("BILLOPS - PROFILE: UAT - Single Database Mode");
        logger.info("========================================");
        logger.info("Database URL: {}", dataSourceUrl);

        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(dataSourceUrl);
        ds.setUsername(dataSourceUsername);
        ds.setPassword(dataSourcePassword);
        ds.setDriverClassName(dataSourceDriver);

        applyHikariSettings(ds, "BillOps-UATPool");

        logger.info("BillOps UAT datasource initialized successfully (No Multi-Tenancy)");
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
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(dataSourceUrl);
        dataSource.setUsername(dataSourceUsername);
        dataSource.setPassword(dataSourcePassword);
        dataSource.setDriverClassName(dataSourceDriver);
        return dataSource;
    }

    /**
     * ========================================================================
     * PRODUCTION PROFILE: Single-Database DataSource
     * ========================================================================
     * Used in production environment.
     * Connects directly to the single production DB — no multi-tenant routing.
     */
    @Bean
    @Primary
    @ConditionalOnProperty(name = "spring.profiles.active", havingValue = "prod", matchIfMissing = false)
    public DataSource prodDataSource() {
        logger.info("========================================");
        logger.info("BILLOPS - PROFILE: PROD - Single Database Mode");
        logger.info("========================================");
        logger.info("Database URL: {}", dataSourceUrl);

        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(dataSourceUrl);
        ds.setUsername(dataSourceUsername);
        ds.setPassword(dataSourcePassword);
        ds.setDriverClassName(dataSourceDriver);

        applyHikariSettings(ds, "BillOps-ProdPool");

        logger.info("BillOps PROD datasource initialized successfully (No Multi-Tenancy)");
        return ds;
    }

    /**
     * ========================================================================
     * PREPROD PROFILE: Single-Database DataSource
     * ========================================================================
     * Used in pre-production environment.
     * Connects directly to the single preprod DB — no multi-tenant routing.
     */
    @Bean
    @Primary
    @ConditionalOnProperty(name = "spring.profiles.active", havingValue = "preprod")
    public DataSource preprodDataSource() {
        logger.info("========================================");
        logger.info("BILLOPS - PROFILE: PREPROD - Single Database Mode");
        logger.info("========================================");
        logger.info("Database URL: {}", dataSourceUrl);

        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(dataSourceUrl);
        ds.setUsername(dataSourceUsername);
        ds.setPassword(dataSourcePassword);
        ds.setDriverClassName(dataSourceDriver);

        applyHikariSettings(ds, "BillOps-PreprodPool");

        logger.info("BillOps PREPROD datasource initialized successfully (No Multi-Tenancy)");
        return ds;
    }

    /**
     * Master DataSource Bean for PROD — points to same single DB as primary.
     */
    @Bean(name = "masterDataSource")
    @ConditionalOnProperty(name = "spring.profiles.active", havingValue = "prod")
    public DataSource masterDataSource() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl(dataSourceUrl);
        ds.setUsername(dataSourceUsername);
        ds.setPassword(dataSourcePassword);
        ds.setDriverClassName(dataSourceDriver);
        return ds;
    }

    /**
     * Master DataSource Bean for PREPROD — points to same single DB as primary.
     */
    @Bean(name = "masterDataSource")
    @ConditionalOnProperty(name = "spring.profiles.active", havingValue = "preprod")
    public DataSource preprodMasterDataSource() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl(dataSourceUrl);
        ds.setUsername(dataSourceUsername);
        ds.setPassword(dataSourcePassword);
        ds.setDriverClassName(dataSourceDriver);
        return ds;
    }
}