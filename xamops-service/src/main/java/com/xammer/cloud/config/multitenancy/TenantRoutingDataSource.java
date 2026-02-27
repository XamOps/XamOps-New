package com.xammer.cloud.config.multitenancy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

public class TenantRoutingDataSource extends AbstractRoutingDataSource {

    private static final Logger log = LoggerFactory.getLogger(TenantRoutingDataSource.class);

    @Override
    protected Object determineCurrentLookupKey() {
        String tenant = TenantContext.getCurrentTenant();
        if (tenant == null || tenant.isEmpty()) {
            log.warn("⚠ TenantContext is null — routing to 'system' DB (fallback). Thread: {}",
                    Thread.currentThread().getName());
            return "system"; // Explicit fallback — avoids relying on lenientFallback=true
        }
        log.debug("Routing DB connection → tenant='{}' thread={}", tenant, Thread.currentThread().getName());
        return tenant;
    }
}
