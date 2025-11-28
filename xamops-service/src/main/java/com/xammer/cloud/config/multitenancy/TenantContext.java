package com.xammer.cloud.config.multitenancy;

public class TenantContext {
    // CHANGE: Use InheritableThreadLocal instead of ThreadLocal
    // This allows child threads (created by @Async) to inherit the Tenant ID
    private static final ThreadLocal<String> CURRENT_TENANT = new InheritableThreadLocal<>();

    public static void setCurrentTenant(String tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    public static String getCurrentTenant() {
        return CURRENT_TENANT.get();
    }

    public static void clear() {
        CURRENT_TENANT.remove();
    }
}