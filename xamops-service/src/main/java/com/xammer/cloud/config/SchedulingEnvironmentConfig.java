package com.xammer.cloud.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration class for environment-aware scheduled job execution.
 * Ensures that scheduled jobs only process data for the tenant associated
 * with the current environment (local -> customer1, uat -> customer2, prod -> customer3).
 *
 * This prevents excessive AWS API costs from hitting all tenants' accounts
 * across all environments.
 */
@Configuration
public class SchedulingEnvironmentConfig {

    private static final Logger logger = LoggerFactory.getLogger(SchedulingEnvironmentConfig.class);

    @Value("${scheduling.enabled:true}")
    private boolean schedulingEnabled;

    @Value("${scheduling.current-tenant-id:customer1}")
    private String currentTenantId;

    @Value("${spring.profiles.active:local}")
    private String activeProfile;

    /**
     * Check if scheduling is enabled in this environment
     */
    public boolean isSchedulingEnabled() {
        return schedulingEnabled;
    }

    /**
     * Get the tenant ID that should be processed in this environment
     */
    public String getCurrentTenantId() {
        return currentTenantId;
    }

    /**
     * Get the active Spring profile (local, uat, prod)
     */
    public String getActiveProfile() {
        return activeProfile;
    }

    /**
     * Determines if the given tenantId should be processed in this environment.
     *
     * @param tenantId The tenant ID to check
     * @return true if this tenant should be processed, false otherwise
     */
    public boolean shouldProcessTenant(String tenantId) {
        if (!schedulingEnabled) {
            logger.info("⚠️ Scheduling is disabled in this environment");
            return false;
        }

        boolean shouldProcess = currentTenantId.equals(tenantId);

        if (!shouldProcess) {
            logger.debug("🔒 Tenant '{}' will be skipped (current environment tenant: '{}')",
                        tenantId, currentTenantId);
        }

        return shouldProcess;
    }

    /**
     * Log the current configuration (useful for debugging)
     */
    public void logConfiguration() {
        logger.info("========================================");
        logger.info("📋 Scheduling Configuration:");
        logger.info("   - Environment: {}", activeProfile);
        logger.info("   - Scheduling Enabled: {}", schedulingEnabled);
        logger.info("   - Current Tenant ID: {}", currentTenantId);
        logger.info("========================================");
    }
}
