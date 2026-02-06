package com.xammer.cloud.service;

import com.xammer.cloud.dto.TenantDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Service
public class EnvironmentService {

    @Autowired
    private Environment environment;

    @Autowired
    @Qualifier("masterDataSource")
    private DataSource masterDataSource;

    public String getCurrentEnvironment() {
        String[] activeProfiles = environment.getActiveProfiles();
        List<String> profiles = Arrays.asList(activeProfiles);

        if (profiles.contains("prod") || profiles.contains("preprod")) {
            return "prod";
        } else if (profiles.contains("uat")) {
            return "uat";
        } else {
            return "local";
        }
    }

    public List<TenantDto> getTenantsForEnvironment() {
        String env = getCurrentEnvironment();

        if ("prod".equals(env)) {
            JdbcTemplate jdbcTemplate = new JdbcTemplate(masterDataSource);
            return jdbcTemplate.query(
                    "SELECT tenant_id, company_name FROM tenant_config WHERE active = true",
                    (rs, rowNum) -> new TenantDto(
                            rs.getString("tenant_id"),
                            rs.getString("company_name")));
        } else {
            // Local/UAT: Return hardcoded tenants as requested
            List<TenantDto> tenants = new ArrayList<>();
            tenants.add(new TenantDto("local-xammer", "Local Xammer (Local Env)"));
            tenants.add(new TenantDto("uat-xammer", "UAT Xammer (UAT Env)"));
            return tenants;
        }
    }
}
