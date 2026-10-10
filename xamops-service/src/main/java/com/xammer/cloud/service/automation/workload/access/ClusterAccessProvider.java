package com.xammer.cloud.service.automation.workload.access;

/**
 * Yields a configured Kubernetes client for one tenant's cluster.
 *
 * <p>The engine itself is a pure function and never touches a cluster; this is the boundary where
 * I/O is allowed to exist. Two implementations ship:
 * {@link EksClusterAccessProvider} (production, the customer's XamOps role plus an EKS access entry)
 * and {@link KubeconfigClusterAccessProvider} (a stored kubeconfig or an explicit API server + CA +
 * ServiceAccount token, for the deferred live-test slice).
 *
 * <p>Selection is by {@link #supports(ClusterTarget)}. No implementation is {@code @Primary}, and
 * neither is invoked while {@code xamops.automation.vertical.enabled} is false — which is its
 * default in every profile.
 */
public interface ClusterAccessProvider {

    /** Stable name for logs and for the API's {@code executionPathDetail}. */
    String providerName();

    /**
     * Whether this provider could serve {@code target}. Structural only — it performs no I/O and
     * makes no promise that {@link #open} will succeed.
     */
    boolean supports(ClusterTarget target);

    /**
     * Opens an authenticated connection. <strong>The caller closes the returned
     * {@link ClusterAccess}.</strong>
     *
     * <p>Implementations must surface exactly the two typed outcomes of
     * {@link ClusterAccessException.Kind} and must not let a raw {@code RuntimeException} escape:
     * the UI's gate rendering depends on being able to tell "add an EKS access entry" from "this
     * cluster is unreachable".
     *
     * @throws ClusterAccessException {@code CLUSTER_ACCESS_NOT_CONFIGURED} or
     *                                {@code CLUSTER_UNREACHABLE}, never anything else
     */
    ClusterAccess open(ClusterTarget target) throws ClusterAccessException;
}
