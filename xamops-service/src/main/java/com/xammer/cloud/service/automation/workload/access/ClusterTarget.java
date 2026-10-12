package com.xammer.cloud.service.automation.workload.access;

/**
 * Everything needed to address one registered cluster, and nothing ambiguous.
 *
 * <p><strong>Canonical identifiers.</strong> {@code clusterConfigId} is
 * {@code kubernetes_cluster_config.id} — a surrogate key, unambiguous by construction — and
 * {@code clientId} is {@code client.id}. {@code awsAccountId} is the <strong>12-digit AWS account
 * number</strong> and is only ever matched against {@code CloudAccount.awsAccountId}.
 *
 * <p>There is deliberately no {@code cloudAccountId} field. {@code kubernetes_cluster_config
 * .cloud_account_id} holds the {@code cloud_account} primary key on modern rows and the AWS account
 * number on legacy ones (audit B4), so a field copying it would carry a value whose meaning depends
 * on when the row was written. Callers resolve the entitled {@code CloudAccount} first and build the
 * target from values taken off a row they are already entitled to.
 *
 * @param clientId        {@code client.id} of the owning tenant; never null
 * @param clusterConfigId {@code kubernetes_cluster_config.id}; never null
 * @param awsAccountId    the 12-digit AWS account number
 * @param clusterName     the EKS cluster name
 * @param region          the AWS region
 */
public record ClusterTarget(Long clientId, Long clusterConfigId, String awsAccountId,
                            String clusterName, String region) {

    public ClusterTarget {
        if (clientId == null) {
            throw new IllegalArgumentException("clientId is mandatory: tenant scoping is not optional");
        }
        if (clusterConfigId == null) {
            throw new IllegalArgumentException("clusterConfigId is mandatory");
        }
        if (clusterName == null || clusterName.isBlank()) {
            throw new IllegalArgumentException("clusterName is mandatory");
        }
    }

    /** For logs: never includes the account number. */
    public String describe() {
        return "cluster " + clusterName + " (config " + clusterConfigId + ", client " + clientId + ")";
    }
}
