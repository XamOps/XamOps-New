package com.xammer.cloud.service;

import com.xammer.cloud.config.SchedulingEnvironmentConfig;
import com.xammer.cloud.dto.TenantDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Service
public class EnvironmentService {

    @Autowired
    private Environment environment;

    @Autowired
    private SchedulingEnvironmentConfig envConfig;

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
        // Single DB mode for all environments — return the configured tenant ID
        String tenantId = envConfig.getCurrentTenantId();
        return Collections.singletonList(new TenantDto(tenantId, tenantId));
    }
}
