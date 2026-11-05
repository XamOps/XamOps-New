package com.xammer.cloud.xampulse.service.query;

import java.time.Instant;
import java.util.List;

/**
 * A validated query scope: account ids the caller has already been authorised for (see
 * {@link QueryScopeFactory}), a bounded time range and optional resource narrowing. Constructing one directly
 * is only for tests and trusted internal callers (the alert evaluator works from a stored, already-authorised rule).
 */
public record QueryScope(List<Long> accountIds, Instant from, Instant to,
                         List<String> regions, List<String> instanceIds,
                         List<String> services, List<String> containers) {

    public QueryScope {
        if (accountIds == null || accountIds.isEmpty()) {
            throw new IllegalArgumentException("QueryScope needs at least one account");
        }
        if (from == null || to == null || !to.isAfter(from)) {
            throw new IllegalArgumentException("QueryScope needs from < to");
        }
        accountIds = List.copyOf(accountIds);
        regions = regions == null ? List.of() : List.copyOf(regions);
        instanceIds = instanceIds == null ? List.of() : List.copyOf(instanceIds);
        services = services == null ? List.of() : List.copyOf(services);
        containers = containers == null ? List.of() : List.copyOf(containers);
    }

    public QueryScope forTenant(long tenantId) {
        return new QueryScope(List.of(tenantId), from, to, regions, instanceIds, services, containers);
    }

    public QueryScope withRange(Instant newFrom, Instant newTo) {
        return new QueryScope(accountIds, newFrom, newTo, regions, instanceIds, services, containers);
    }

    public long rangeSeconds() {
        return Math.max(1, to.getEpochSecond() - from.getEpochSecond());
    }

    public static QueryScope of(List<Long> accountIds, Instant from, Instant to) {
        return new QueryScope(accountIds, from, to, List.of(), List.of(), List.of(), List.of());
    }
}
