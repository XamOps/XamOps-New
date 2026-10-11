package com.xammer.cloud.exception;

import org.springframework.http.HttpStatus;

/**
 * Every reason CloudK8s automation can be refused or disabled for, shared with the UI.
 *
 * <p>
 * Two groups, in this order (design §6.1):
 * <ul>
 *   <li><b>Gate codes</b> ({@link #gate()} is true): reasons a control is disabled. The UI
 *       renders each with fixed copy; the server sends the subset it enforces as {@code 403}
 *       refusals. Order is the §6.1 table order.</li>
 *   <li><b>Error codes</b>: refusals that never disable a control and never appear in
 *       capabilities ({@code UNAUTHENTICATED} through {@code INTERNAL}).</li>
 * </ul>
 *
 * <p>
 * The wire code is {@code "K8S_" + name()}. The shared fixture
 * {@code xamops-service/src/test/resources/k8s/k8s-reason-codes.json} lists both groups;
 * {@code K8sAutomationReasonContractTest} (JUnit) and {@code reasonCodes.contract.test.ts}
 * (vitest) each fail when their side drifts from it. Add a code here, in the fixture and in
 * the frontend {@code REASON_CODES} together.
 *
 * <p>
 * Public and self-contained so the vertical engine can reuse it instead of re-declaring the
 * codes (design §6.6, R2).
 */
public enum K8sAutomationReason {

    // ── Gate codes (design §6.1 table order) ────────────────────────────────
    DEMO(true, HttpStatus.FORBIDDEN, "Demo workspace. Changes aren't saved."),
    PROVIDER_UNSUPPORTED(true, HttpStatus.FORBIDDEN, "Automation is available for EKS clusters."),
    CONTROLLER_NOT_READY(true, HttpStatus.FORBIDDEN,
            "Automated apply is disabled until this controller passes safety validation."),
    ANALYSE_ONLY(true, HttpStatus.FORBIDDEN,
            "Live consolidation is disabled until pod-fit simulation is available."),
    NO_USER_PERMISSION(true, HttpStatus.FORBIDDEN, "You don't have permission to change automation."),
    READ_ONLY_CONNECTION(true, HttpStatus.FORBIDDEN,
            "Read-only connection. Automation changes need read-write access."),
    CONNECTION_NOT_VERIFIED(true, HttpStatus.FORBIDDEN,
            "This connection isn't verified. Reconnect the account to change automation."),
    AGENT_NOT_INSTALLED(true, HttpStatus.FORBIDDEN, "The automation agent isn't installed on this cluster."),
    CLUSTER_ACCESS_NOT_CONFIGURED(true, HttpStatus.FORBIDDEN,
            "Direct access isn't set up: no EKS access entry maps the XamOps role."),
    CLUSTER_UNREACHABLE(true, HttpStatus.FORBIDDEN,
            "Direct access isn't possible: the API server isn't reachable from XamOps (private endpoint)."),
    NO_METRICS_SOURCE(true, HttpStatus.FORBIDDEN,
            "No usage metrics source for this cluster, so XamOps can't compute recommendations."),
    INSUFFICIENT_HISTORY(true, HttpStatus.FORBIDDEN, "Not enough usage history yet."),
    GITOPS_MANAGED(true, HttpStatus.FORBIDDEN, "Managed by Argo CD or Flux. XamOps won't patch its template."),
    MISSING_AWS_PERMISSION(true, HttpStatus.FORBIDDEN, "XamOps can't read a required AWS action in this account."),
    UNKNOWN(true, HttpStatus.FORBIDDEN, "Couldn't check automation access."),
    GUARDRAIL_LOCKED(true, HttpStatus.FORBIDDEN, "Always on while automation lacks pod-fit simulation."),

    // ── Error codes (never gates; design §6.1 "Error codes that are not gates") ──
    UNAUTHENTICATED(false, HttpStatus.UNAUTHORIZED, "Sign in to continue."),
    INVALID_REQUEST(false, HttpStatus.BAD_REQUEST, "The request isn't valid."),
    INVALID_POLICY(false, HttpStatus.BAD_REQUEST, "The request body isn't valid for this setting."),
    NO_ACCOUNT_ACCESS(false, HttpStatus.FORBIDDEN, "You don't have access to this account."),
    CLUSTER_NOT_REGISTERED(false, HttpStatus.NOT_FOUND,
            "This cluster isn't registered with XamOps yet. Install the XamOps agent first."),
    INTERNAL(false, HttpStatus.INTERNAL_SERVER_ERROR, "Couldn't load automation data.");

    /** Prefix of every wire code. */
    public static final String CODE_PREFIX = "K8S_";

    /** Fixed {@link #INTERNAL} message for a failed mutation (the default covers reads). */
    public static final String INTERNAL_MUTATION_MESSAGE = "Couldn't save the change. Try again.";

    private final boolean gate;
    private final HttpStatus status;
    private final String defaultMessage;

    K8sAutomationReason(boolean gate, HttpStatus status, String defaultMessage) {
        this.gate = gate;
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    /** True for the §6.1 gate codes; false for the six error codes. */
    public boolean gate() {
        return gate;
    }

    /** The wire code, {@code "K8S_" + name()}. */
    public String code() {
        return CODE_PREFIX + name();
    }

    /** HTTP status the server answers with when it refuses for this reason. */
    public HttpStatus status() {
        return status;
    }

    /** Fixed, user-facing copy. Never contains request data or exception text. */
    public String defaultMessage() {
        return defaultMessage;
    }
}
