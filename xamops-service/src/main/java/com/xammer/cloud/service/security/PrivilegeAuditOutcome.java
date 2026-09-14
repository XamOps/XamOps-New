package com.xammer.cloud.service.security;

/**
 * Whether a permission audit could see everything it needed to.
 *
 * <p>
 * Shared by the AWS, GCP and Azure analyzers rather than nested inside each one. The three
 * providers have nothing in common in how they express permission — IAM policy documents,
 * role bindings, and RBAC action lists — but they share the one property the UI actually
 * branches on, and three separate {@code Outcome} records would drift the moment one provider
 * gained a field.
 *
 * <p>
 * The distinction this carries is the whole reason it exists. An account with no findings and
 * an account nobody was allowed to inspect both render as a page of unflagged cards. Only this
 * flag lets the console say which one it is showing, and getting it wrong means telling a
 * customer their identities are clean when the audit never ran.
 *
 * @param degraded true when at least one permission source could not be read, so some
 *                 identities are "unknown" rather than "clean"
 * @param note     customer-facing explanation, null when not degraded
 */
public record PrivilegeAuditOutcome(boolean degraded, String note) {

    private static final PrivilegeAuditOutcome CLEAN = new PrivilegeAuditOutcome(false, null);

    /** The audit read every permission source it needed. */
    public static PrivilegeAuditOutcome complete() {
        return CLEAN;
    }

    /** The audit was blocked somewhere; {@code note} must say where and what to do about it. */
    public static PrivilegeAuditOutcome degraded(String note) {
        return new PrivilegeAuditOutcome(true, note);
    }
}
