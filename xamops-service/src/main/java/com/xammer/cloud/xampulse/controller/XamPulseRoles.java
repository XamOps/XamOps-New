package com.xammer.cloud.xampulse.controller;

/**
 * Method-security expressions for XamPulse controllers (plan 8.8). This deployment has a single XamOps product
 * role, {@code ROLE_XAMOPS}: it is both viewer and account operator (onboarding, bucket selection, channels).
 * {@code ROLE_SUPER_ADMIN} is the platform superuser and may also act.
 */
public final class XamPulseRoles {

    /** Reads and editing of own rules, silences and saved views. */
    public static final String VIEWER = "hasAnyAuthority('ROLE_XAMOPS','ROLE_SUPER_ADMIN')";

    /** Channels (they hold secrets), audit log, onboarding/bucket selection and other administrative actions. */
    public static final String ADMIN = "hasAnyAuthority('ROLE_XAMOPS','ROLE_SUPER_ADMIN')";

    private XamPulseRoles() {}
}
