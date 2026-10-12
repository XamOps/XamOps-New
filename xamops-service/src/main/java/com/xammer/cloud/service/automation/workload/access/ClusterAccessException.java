package com.xammer.cloud.service.automation.workload.access;

/**
 * The <strong>only</strong> failure a {@link ClusterAccessProvider} may raise from
 * {@link ClusterAccessProvider#open(ClusterTarget)}.
 *
 * <p>Two typed kinds, both of which are shared reason codes the UI already renders with their own
 * copy and remedy. That is the point: a generic {@code RuntimeException} leaking out of a provider
 * would reach the user as "something went wrong", and the UI could not tell "add an EKS access
 * entry" from "this is a private endpoint, use the agent" — two different problems with two
 * different fixes.
 */
public class ClusterAccessException extends Exception {

    /** 1:1 with the shared reason codes of the same names. */
    public enum Kind {
        /**
         * XamOps has no usable credential path into this cluster's API: no EKS access entry maps the
         * XamOps role, the role is denied {@code eks:DescribeCluster}, the cluster does not exist,
         * the account is not registered, no CA is available, or the API server answered 401/403.
         *
         * <p>Remedy is configuration the customer performs.
         */
        CLUSTER_ACCESS_NOT_CONFIGURED,

        /**
         * Access looks configured, but the API server could not be reached: connect timeout, DNS
         * failure, or a private endpoint with no network path from XamOps.
         *
         * <p>Remedy is a network path, or the agent-mediated execution path.
         */
        CLUSTER_UNREACHABLE
    }

    private final Kind kind;

    public ClusterAccessException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public ClusterAccessException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    /** The shared reason code, without the {@code K8S_} prefix, as it appears in payload bodies. */
    public String reasonCode() {
        return kind.name();
    }
}
