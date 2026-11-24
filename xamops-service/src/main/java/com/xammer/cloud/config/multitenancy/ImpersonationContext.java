package com.xammer.cloud.config.multitenancy;

public class ImpersonationContext {
    // CHANGE: Use InheritableThreadLocal here too
    private static final ThreadLocal<Long> IMPERSONATED_USER_ID = new InheritableThreadLocal<>();

    /**
     * Whether the header impersonation on this thread was started by a {@code ROLE_SUPER_ADMIN}. Set only by
     * {@link ImpersonationFilter}, never by {@link ImpersonationJwtFilter}; {@code XamLensTabGuard} reads it.
     * A plain ThreadLocal, unlike the id above: it widens access, so a thread created while it is set must
     * not inherit it.
     */
    private static final ThreadLocal<Boolean> STARTED_BY_SUPER_ADMIN = new ThreadLocal<>();

    public static void setImpersonatedUserId(Long userId) {
        IMPERSONATED_USER_ID.set(userId);
    }

    public static Long getImpersonatedUserId() {
        return IMPERSONATED_USER_ID.get();
    }

    public static void setStartedBySuperAdmin(boolean startedBySuperAdmin) {
        STARTED_BY_SUPER_ADMIN.set(startedBySuperAdmin);
    }

    public static boolean isStartedBySuperAdmin() {
        return Boolean.TRUE.equals(STARTED_BY_SUPER_ADMIN.get());
    }

    public static void clear() {
        IMPERSONATED_USER_ID.remove();
        STARTED_BY_SUPER_ADMIN.remove();
    }
}
