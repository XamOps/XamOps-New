package com.xammer.cloud.dto.war;

/**
 * Outcome of a single WAR (Workload Attack-surface Review) control evaluation.
 *
 * <p>
 * WAR deliberately distinguishes "we checked and it is fine" from "we could not
 * check". A dashboard that silently renders an unverifiable control as green is
 * worse than one that says nothing: it tells a customer they are protected
 * against credential theft or crypto-mining when nobody has actually looked.
 */
public enum WarStatus {

    /** Evaluated against live cloud state and compliant. */
    PASS,

    /** Evaluated against live cloud state and non-compliant — this is exposed attack surface. */
    FAIL,

    /** Partially compliant, or compliant but weakened (e.g. endpoint public but CIDR-restricted). */
    WARN,

    /** No automated check exists; the control needs a human review. */
    MANUAL,

    /** The check ran but the account lacks the IAM permission, or the API errored. */
    UNKNOWN,

    /** The service the control governs is not in use in this account. */
    NOT_APPLICABLE
}
