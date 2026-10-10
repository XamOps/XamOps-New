package com.xammer.cloud.service.automation.workload.api;

/**
 * The nine gate reason codes shared <strong>verbatim</strong> with the frontend.
 *
 * <p>The constant names <em>are</em> the wire values, and
 * {@code WorkloadReasonCodeContractTest} asserts the list and its order, so a rename cannot
 * silently break the UI's gate rendering.
 *
 * <p>Emitted <strong>without</strong> the {@code K8S_} prefix inside payload bodies
 * ({@code gateReasons}, per-workload reasons, {@code executionPathDetail.*.reason}) and
 * <strong>with</strong> it in a refusal's {@code code} field. The UI parses both.
 *
 * <p><strong>The engine introduces no new shared gate codes.</strong> The agent-path state reuses the
 * UI's existing {@code AGENT_NOT_INSTALLED} and direct-path failures reuse the two cluster-access
 * codes, so the UI's reason map needs no additions to render this feature correctly.
 *
 * <p>The shared fixture at {@code src/test/resources/k8s/k8s-reason-codes.json} belongs to the
 * CloudK8s UI branch and is deliberately not created here, to avoid a merge conflict on a file that
 * branch owns. Folding this enum into it is a follow-up once both land.
 */
public enum ReasonCode {

    /** The AWS account is connected read-only, so automation changes are not possible. */
    READ_ONLY_CONNECTION,

    /** This user may view automation but not change it. */
    NO_USER_PERMISSION,

    /** The controller behind this action has not passed safety validation, or is not built. */
    CONTROLLER_NOT_READY,

    /** The shared demo identity; nothing is saved. */
    DEMO,

    /** No EKS access entry maps the XamOps role into this cluster. */
    CLUSTER_ACCESS_NOT_CONFIGURED,

    /** Access is configured but the API server cannot be reached — typically a private endpoint. */
    CLUSTER_UNREACHABLE,

    /** No usage source can supply percentiles for this cluster. */
    NO_METRICS_SOURCE,

    /** Not enough usage history yet for the policy window. A waiting state, not an error. */
    INSUFFICIENT_HISTORY,

    /** Argo CD or Flux owns this workload's template, so XamOps will not patch it. */
    GITOPS_MANAGED;

    /** The UI's existing agent-channel code. Reused rather than duplicated as a new engine code. */
    public static final String AGENT_NOT_INSTALLED = "AGENT_NOT_INSTALLED";

    /** The form used in a refusal's {@code code} field. */
    public String refusalCode() {
        return "K8S_" + name();
    }
}
