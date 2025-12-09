package com.xammer.cloud.service;

import com.xammer.cloud.config.multitenancy.TenantContext;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.dto.GlobalUserDto;
import com.xammer.cloud.dto.TenantDto;
import com.xammer.cloud.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;

@Service
public class GlobalUserSyncService {

    private static final Logger log = LoggerFactory.getLogger(GlobalUserSyncService.class);

    @Autowired
    private MasterDatabaseService masterDatabaseService;

    @Autowired
    private UserRepository userRepository;

    // ✅ FIX: Inject DataSource directly instead of Controller to avoid
    // SecurityContext issues
    @Autowired
    private TenantService tenantService; // Service layer without security restrictions

    /**
     * Runs automatically every 60 seconds to ensure Global Directory is up to date.
     */
    @Scheduled(fixedDelay = 60000) // Run every 1 minute
    public void syncAllUsersToGlobalDirectory() {
        log.info("↻ Starting Global User Synchronization...");

        // 1. Get all Active Tenants + Default (Master)
        List<TenantDto> tenants = tenantService.getAllActiveTenants();

        // Add "default" manually to sync SuperAdmins from Master DB
        tenants.add(new TenantDto("default", "System Master"));

        for (TenantDto tenant : tenants) {
            syncTenantUsers(tenant.getTenantId());
        }

        log.info("✓ Global User Synchronization Complete.");
    }

    private void syncTenantUsers(String tenantId) {
        // 1. Switch Context to specific Tenant
        boolean isDefault = "default".equals(tenantId);
        if (isDefault) {
            TenantContext.clear();
        } else {
            TenantContext.setCurrentTenant(tenantId);
        }

        try {
            // 2. Fetch all users from that Tenant's Database
            List<User> localUsers = userRepository.findAll();

            log.debug("Found {} users in tenant '{}'", localUsers.size(), tenantId);

            // 3. Push to Master DB
            for (User localUser : localUsers) {
                try {
                    // Check if user already exists in Global DB
                    Optional<GlobalUserDto> existing = masterDatabaseService.findGlobalUser(localUser.getUsername());

                    if (existing.isEmpty()) {
                        log.info("➕ Syncing new user '{}' from Tenant '{}' to Global Directory",
                                localUser.getUsername(), tenantId);
                        masterDatabaseService.registerGlobalUser(
                                localUser.getUsername(),
                                localUser.getPassword(), // Hashed password
                                localUser.getEmail(),
                                localUser.getRole(),
                                tenantId);
                    }
                } catch (Exception e) {
                    log.error("Failed to sync user '{}' from tenant '{}': {}",
                            localUser.getUsername(), tenantId, e.getMessage());
                }
            }
        } catch (org.springframework.transaction.CannotCreateTransactionException e) {
            // Database connection issue - log concisely without full stack trace
            log.warn(
                    "⚠️ Cannot connect to database for tenant '{}' - skipping (connection timeout or database unavailable)",
                    tenantId);
        } catch (Exception e) {
            // Other unexpected errors - log with details
            log.error("❌ Unexpected error accessing database for tenant '{}': {}", tenantId, e.getMessage());
        } finally {
            // 4. Always clear context
            TenantContext.clear();
        }
    }
}