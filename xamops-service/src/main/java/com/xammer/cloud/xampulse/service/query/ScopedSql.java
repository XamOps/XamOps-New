package com.xammer.cloud.xampulse.service.query;

import java.util.List;

/**
 * A ClickHouse statement bound to the tenants it may read. Only {@link ClickHouseExecutor} runs these.
 *
 * <p>Invariants (asserted by the executor and by the builder tests): the text contains the marker
 * {@code tenant_id IN (}, and the first {@code tenantIds.size()} parameters are exactly the tenant ids, so
 * no placeholder precedes the tenant predicate. All user-influenced values are parameters.
 */
public record ScopedSql(String sql, List<Object> params, List<Long> tenantIds) {

    public static final String TENANT_MARKER = "tenant_id IN (";

    public ScopedSql {
        params = List.copyOf(params);
        tenantIds = List.copyOf(tenantIds);
    }
}
