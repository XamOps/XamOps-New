package com.xammer.cloud.service;

import com.xammer.cloud.dto.TenantDto;
import org.springframework.beans.factory.annotation.Autowired;
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
    private DataSource dataSource;

    /**
     * Fetches all active tenants from the master database.
     * This method is NOT secured and can be called from scheduled tasks.
     * 
     * @return List of active tenants
     */
    public List<TenantDto> getAllActiveTenants() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        return jdbcTemplate.query(
                "SELECT tenant_id, company_name FROM tenant_config WHERE active = true",
                (rs, rowNum) -> new TenantDto(
                        rs.getString("tenant_id"),
                        rs.getString("company_name")));
    }
}
