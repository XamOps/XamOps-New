package com.xammer.cloud.config.multitenancy;

public class ImpersonationContext {
    // CHANGE: Use InheritableThreadLocal here too
    private static final ThreadLocal<Long> IMPERSONATED_USER_ID = new InheritableThreadLocal<>();

    public static void setImpersonatedUserId(Long userId) {
        IMPERSONATED_USER_ID.set(userId);
    }

    public static Long getImpersonatedUserId() {
        return IMPERSONATED_USER_ID.get();
    }

    public static void clear() {
        IMPERSONATED_USER_ID.remove();
    }
}