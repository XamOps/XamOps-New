package com.xammer.cloud.dto.agent;

/**
 * Who an agent request actually is, as established by its API key.
 *
 * The agent API used to authenticate with a boolean: the key was either the one shared global
 * key or it was wrong. Identity came from {@code clusterName} and {@code cloudAccountId} in the
 * request body, which the caller supplies and nothing verified. Since the shared key ships in
 * every public install script and in the frontend bundle, that made the account id an
 * unauthenticated parameter — a caller could push data as any cluster, or ask for any account's
 * observability credential.
 *
 * Authentication now resolves to one of these instead, so a handler can compare what the caller
 * claims against what its key actually entitles it to.
 */
public final class AgentPrincipal {

    public enum Scope {
        /** Key belongs to one Kubernetes cluster (kubernetes_cluster_config.agent_api_key). */
        CLUSTER,
        /** Key belongs to one cloud account (cloud_account.agent_api_key) — VM agents. */
        ACCOUNT,
        /**
         * The shared global key. Authenticates, but proves nothing about identity, so it can
         * never satisfy an ownership check. Accepted only while
         * {@code xamops.agent.legacy-global-key.enabled} is true.
         */
        LEGACY_GLOBAL
    }

    private final Scope scope;
    /** Provider account id (AWS account number / GCP project / Azure subscription), or null for legacy. */
    private final String providerAccountId;
    /** Cluster name for CLUSTER scope, otherwise null. */
    private final String clusterName;

    private AgentPrincipal(Scope scope, String providerAccountId, String clusterName) {
        this.scope = scope;
        this.providerAccountId = providerAccountId;
        this.clusterName = clusterName;
    }

    public static AgentPrincipal cluster(String providerAccountId, String clusterName) {
        return new AgentPrincipal(Scope.CLUSTER, providerAccountId, clusterName);
    }

    public static AgentPrincipal account(String providerAccountId) {
        return new AgentPrincipal(Scope.ACCOUNT, providerAccountId, null);
    }

    public static AgentPrincipal legacyGlobal() {
        return new AgentPrincipal(Scope.LEGACY_GLOBAL, null, null);
    }

    public Scope getScope() { return scope; }
    public String getProviderAccountId() { return providerAccountId; }
    public String getClusterName() { return clusterName; }

    /** True when this key proves which account it belongs to. */
    public boolean isScoped() { return scope != Scope.LEGACY_GLOBAL; }

    /**
     * Whether this principal may act on the given account.
     *
     * The legacy shared key is allowed through because it predates any notion of ownership and
     * turning it into a denial would take every existing agent offline on deploy. It is logged
     * at every use so the remaining users can be found and migrated, after which the key is
     * switched off and this method becomes a real boundary for every caller.
     */
    public boolean canAct(String claimedAccountId) {
        if (scope == Scope.LEGACY_GLOBAL) return true;
        if (claimedAccountId == null || claimedAccountId.isBlank()) return false;
        return normalise(claimedAccountId).equals(normalise(providerAccountId));
    }

    /**
     * Whether this principal may act on the given cluster within the given account.
     *
     * An account-scoped key may act on any cluster in its own account; a cluster-scoped key is
     * limited to its own cluster.
     */
    public boolean canActOnCluster(String claimedAccountId, String claimedClusterName) {
        if (!canAct(claimedAccountId)) return false;
        if (scope != Scope.CLUSTER) return true;
        return claimedClusterName != null && claimedClusterName.equals(clusterName);
    }

    /**
     * AWS account ids lose leading zeros whenever they pass through a numeric type, so the same
     * account arrives as both "080310056620" and "80310056620" depending on the path it took.
     * Comparing raw strings would reject a legitimate agent for an account whose id starts with
     * a zero — a bug that would only ever appear for a subset of customers.
     */
    private static String normalise(String accountId) {
        if (accountId == null) return "";
        String trimmed = accountId.trim();
        if (trimmed.chars().allMatch(Character::isDigit)) {
            String stripped = trimmed.replaceFirst("^0+", "");
            return stripped.isEmpty() ? "0" : stripped;
        }
        return trimmed;
    }

    /** Safe for logs — never includes the key itself. */
    @Override
    public String toString() {
        return switch (scope) {
            case CLUSTER -> "cluster:" + clusterName + "@" + providerAccountId;
            case ACCOUNT -> "account:" + providerAccountId;
            case LEGACY_GLOBAL -> "legacy-global-key";
        };
    }
}
