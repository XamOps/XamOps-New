package com.xammer.billops.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;

/**
 * Configuration class for environment-aware logging of scheduled jobs.
 */
@Configuration
public class BillopsSchedulingEnvironmentConfig {

    private static final Logger logger = LoggerFactory.getLogger(BillopsSchedulingEnvironmentConfig.class);

    @Value("${scheduling.enabled:true}")
    private boolean schedulingEnabled;

    @Value("${scheduling.current-tenant-id:customer1}")
    private String currentTenantId;

    @Value("${spring.profiles.active:local}")
    private String activeProfile;

    @PostConstruct
    public void init() {
        logConfiguration();
    }

    public void logConfiguration() {
        logger.info("========================================");
        logger.info("📋 BillOps Scheduling Configuration:");
        logger.info("   - Environment: {}", activeProfile);
        logger.info("   - Scheduling Enabled: {}", schedulingEnabled);
        logger.info("   - Current Tenant ID: {}", currentTenantId);
        logger.info("========================================");
    }

    public boolean isSchedulingEnabled() {
        return schedulingEnabled;
    }

    public String getCurrentTenantId() {
        return currentTenantId;
    }

    public String getActiveProfile() {
        return activeProfile;
    }
}
