package com.xammer.cloud.config.multitenancy;

public class TenantContext {
    // InheritableThreadLocal propagates context to child threads at thread-creation time.
    // For thread pool reuse, a TaskDecorator is also required (see AsyncConfig.java).
    private static final InheritableThreadLocal<String> CURRENT_TENANT = new InheritableThreadLocal<>();

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