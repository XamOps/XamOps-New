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
        // Single-tenant mode: always return empty list — no tenant iteration
        return java.util.Collections.emptyList();
    }
}
