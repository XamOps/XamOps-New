package com.xammer.cloud.config.multitenancy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Utility that wraps an Executor so that the current tenant context AND MDC map
 * are captured at the call site and restored on the executing thread.
 *
 * Use this when scheduling CompletableFuture callbacks (.thenApplyAsync /
 * .thenComposeAsync) that need to access the tenant-routed DataSource.
 * The TaskDecorator in AsyncConfig only covers the initial @Async method
 * invocation — subsequent callbacks on pool threads need explicit propagation.
 */
public final class TenantContextUtil {

    private static final Logger log = LoggerFactory.getLogger(TenantContextUtil.class);

    private TenantContextUtil() {}

    /**
     * Returns an Executor backed by {@code base} that propagates the
     * tenant ID and MDC context captured right now (call-site thread) into each submitted task.
     *
     * Logs a WARN if tenant is null at capture time — this means the callback will
     * route to the 'system' fallback DB instead of the correct customer DB.
     */
    public static Executor tenantAware(Executor base) {
        String tenant = TenantContext.getCurrentTenant();
        Map<String, String> mdcContext = MDC.getCopyOfContextMap();

        if (tenant == null) {
            log.warn("TenantContextUtil: tenant is null at capture point — callback will route to 'system' DB. Thread: {}",
                    Thread.currentThread().getName());
        }

        return task -> base.execute(() -> {
            try {
                if (tenant != null) {
                    TenantContext.setCurrentTenant(tenant);
                }
                if (mdcContext != null) {
                    MDC.setContextMap(mdcContext);
                } else if (tenant != null) {
                    MDC.put("tenant", tenant);
                }
                task.run();
            } finally {
                TenantContext.clear();
                MDC.clear();
            }
        });
    }
}
