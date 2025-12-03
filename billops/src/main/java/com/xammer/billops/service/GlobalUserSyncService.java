package com.xammer.billops.service;

import com.xammer.billops.config.multitenancy.TenantContext;
import com.xammer.cloud.domain.User; // Assuming shared domain object or similar structure
import com.xammer.billops.dto.GlobalUserDto;
import com.xammer.billops.dto.TenantDto;
import com.xammer.billops.repository.UserRepository;
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
    @Qualifier("masterDataSource")
    private DataSource masterDataSource;

    /**
     * Runs automatically every 60 seconds to ensure Global Directory is up to date.
     */
    @Scheduled(fixedDelay = 60000) // Run every 1 minute
    public void syncAllUsersToGlobalDirectory() {
        log.info("↻ Starting Global User Synchronization...");

        // 1. Get all Active Tenants directly from DB (Bypassing Controller Security)
        JdbcTemplate jdbcTemplate = new JdbcTemplate(masterDataSource);
        List<TenantDto> tenants = jdbcTemplate.query(
                "SELECT tenant_id, company_name FROM tenant_config WHERE active = true",
                (rs, rowNum) -> new TenantDto(
                        rs.getString("tenant_id"),
                        rs.getString("company_name")));

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
                    log.error("Failed to sync user: " + localUser.getUsername(), e);
                }
            }
        } catch (Exception e) {
            log.error("Error accessing database for tenant: " + tenantId, e);
        } finally {
            // 4. Always clear context
            TenantContext.clear();
        }
    }
}