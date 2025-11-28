package com.xammer.cloud.config.multitenancy;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

public class TenantRoutingDataSource extends AbstractRoutingDataSource {
    @Override
    protected Object determineCurrentLookupKey() {
        return TenantContext.getCurrentTenant();
    }
}