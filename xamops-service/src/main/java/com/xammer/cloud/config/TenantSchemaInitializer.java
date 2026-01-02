package com.xammer.cloud.config;

import com.xammer.cloud.config.multitenancy.TenantContext;
import com.xammer.cloud.dto.TenantDto;
import com.xammer.cloud.service.TenantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;

/**
 * Ensures that CloudSitter tables exist in all tenant databases.
 * Use this as a temporary fix/migration until Flyway/Liquibase is set up for
 * multi-tenancy.
 */
@Configuration
@Order(2) // Run after DataInitializer
public class TenantSchemaInitializer implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(TenantSchemaInitializer.class);

    private final TenantService tenantService;
    private final DataSource dataSource;

    public TenantSchemaInitializer(TenantService tenantService, DataSource dataSource) {
        this.tenantService = tenantService;
        this.dataSource = dataSource;
    }

    @Override
    public void run(String... args) throws Exception {
        logger.info("Starting Tenant Schema Initialization...");

        List<TenantDto> tenants = tenantService.getAllActiveTenants();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        for (TenantDto tenant : tenants) {
            String tenantId = tenant.getTenantId();
            try {
                TenantContext.setCurrentTenant(tenantId);
                logger.info("Initializing schema for tenant: {}", tenantId);

                // 1. Create CloudsitterPolicy Table
                jdbcTemplate.execute("""
                            CREATE TABLE IF NOT EXISTS cloudsitter_policies (
                                id BIGSERIAL PRIMARY KEY,
                                name VARCHAR(255),
                                description VARCHAR(255),
                                type VARCHAR(255),
                                time_zone VARCHAR(255),
                                schedule_json TEXT,
                                notifications_enabled BOOLEAN,
                                notification_email VARCHAR(255),
                                client_id BIGINT
                            );
                        """);

                // 2. Create CloudsitterAssignment Table
                jdbcTemplate.execute("""
                            CREATE TABLE IF NOT EXISTS cloudsitter_assignments (
                                id BIGSERIAL PRIMARY KEY,
                                resource_id VARCHAR(255),
                                account_id VARCHAR(255),
                                region VARCHAR(255),
                                policy_id BIGINT,
                                active BOOLEAN,
                                FOREIGN KEY (policy_id) REFERENCES cloudsitter_policies(id)
                            );
                        """);

                logger.info("Schema initialized for tenant: {}", tenantId);

            } catch (Exception e) {
                logger.error("Failed to initialize schema for tenant {}", tenantId, e);
            } finally {
                TenantContext.clear();
            }
        }
        logger.info("Tenant Schema Initialization Complete.");
    }
}
