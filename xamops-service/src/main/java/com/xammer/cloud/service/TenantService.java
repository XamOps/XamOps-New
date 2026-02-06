package com.xammer.cloud.service;

import com.xammer.cloud.dto.TenantDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.List;

/**
 * Service for tenant-related operations.
 * This service is NOT secured, allowing scheduled tasks and internal operations
 * to access tenant data without authentication context.
 */
@Service
public class TenantService {

    @Autowired
    @Qualifier("masterDataSource")
    private DataSource masterDataSource;

    @Autowired
    private org.springframework.core.env.Environment environment;

    /**
     * Fetches all active tenants from the master database.
     * This method is NOT secured and can be called from scheduled tasks.
     * 
     * IMPORTANT: Only works in PROD profile (returns empty list in local/UAT)
     * 
     * @return List of active tenants
     */
    public List<TenantDto> getAllActiveTenants() {
        // Check if we're in prod or preprod profile
        String[] activeProfiles = environment.getActiveProfiles();
        List<String> profilesList = java.util.Arrays.asList(activeProfiles);
        boolean isSyncProfile = profilesList.contains("prod") || profilesList.contains("preprod");

        if (!isSyncProfile) {
            // In local/UAT, there's no tenant_config table - return empty list
            return java.util.Collections.emptyList();
        }

        // In prod, query tenant_config from master DB using masterDataSource
        JdbcTemplate jdbcTemplate = new JdbcTemplate(masterDataSource);
        return jdbcTemplate.query(
                "SELECT tenant_id, company_name FROM tenant_config WHERE active = true",
                (rs, rowNum) -> new TenantDto(
                        rs.getString("tenant_id"),
                        rs.getString("company_name")));
    }
}
