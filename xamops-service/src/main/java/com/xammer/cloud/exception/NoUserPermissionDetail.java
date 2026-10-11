package com.xammer.cloud.exception;

/**
 * Why {@link K8sAutomationReason#NO_USER_PERMISSION} applies. Sent as the refusal's and the
 * capabilities response's {@code detail}; the UI picks its copy from it (design §6.1, D2).
 */
public enum NoUserPermissionDetail {

    /** A restricted user (has tab rows) without {@code cloudops-cloudk8s-automation}. */
    TAB_GRANT("You don't have permission to change automation."),
    /** The user's role can view automation but not change it (BillOps-only or unknown roles). */
    ROLE("Your role can view automation but not change it."),
    /** The session is impersonating another user. */
    IMPERSONATION("Automation changes aren't available while you're signed in as another user.");

    private final String message;

    NoUserPermissionDetail(String message) {
        this.message = message;
    }

    /** Fixed copy for this detail. */
    public String message() {
        return message;
    }
}
