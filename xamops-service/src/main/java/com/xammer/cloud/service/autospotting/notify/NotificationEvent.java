package com.xammer.cloud.service.autospotting.notify;

/**
 * The customer notice kinds of design-ux-ops.md section 7.2 and PO-24. {@code mandatory} notices ignore the
 * customer's AutoSpotting email opt-out; PO-24 allows opting out of everything except failures. The design's
 * table also marks {@code CONFIG_DEFERRED_BY_FALLBACK}, {@code DEGRADED_LONG}, {@code DRIFT_PAUSED} and
 * {@code ADMIN_ENABLED} as mandatory: each tells the customer something their configuration or access did
 * that they did not do themselves, so they are kept mandatory as designed.
 */
public enum NotificationEvent {
    PLAN_APPLIED(false, "Plan applied"),
    APPLY_FAILED(true, "Apply failed"),
    FALLBACK_ENTERED(false, "Fallback entered"),
    CONFIG_DEFERRED_BY_FALLBACK(true, "Configuration change deferred"),
    FALLBACK_EXITED(false, "Fallback exited"),
    DEGRADED_LONG(true, "Group degraded"),
    REVERT_STARTED(false, "Revert started"),
    REVERT_COMPLETED(false, "Revert completed"),
    REVERT_FAILED(true, "Revert failed"),
    DRIFT_PAUSED(true, "Paused: configuration changed outside XamOps"),
    ADMIN_ENABLED(true, "Enabled by an administrator"),
    STATEMENT_READY(false, "Statement ready"),
    DISPUTE_UPDATED(false, "Dispute updated");

    private final boolean mandatory;
    private final String title;

    NotificationEvent(boolean mandatory, String title) {
        this.mandatory = mandatory;
        this.title = title;
    }

    public boolean mandatory() {
        return mandatory;
    }

    /** The short heading used in the subject, the email and the in-app list. */
    public String title() {
        return title;
    }

    /** Finance notices go to the billing contact when one is recorded, else the account recipients. */
    public boolean finance() {
        return this == STATEMENT_READY || this == DISPUTE_UPDATED;
    }
}
