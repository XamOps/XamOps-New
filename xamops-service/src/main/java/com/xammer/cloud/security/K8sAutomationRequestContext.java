package com.xammer.cloud.security;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesClusterConfig;

import java.util.List;
import java.util.Optional;

/**
 * What {@code K8sAutomationGuardInterceptor} resolved for one {@code /api/eks/autoscaling/**}
 * request (design §6.6 step 5). Controllers read it with
 * {@code @RequestAttribute(K8sAutomationRequestContext.ATTRIBUTE)} and never parse
 * {@code accountId} again.
 *
 * @param user     the authenticated principal
 * @param accounts the AWS {@code CloudAccount} rows the user is entitled to for the requested
 *                 account number, the row that owns {@code config} first
 * @param config   the cluster config, or {@code null} when none is registered
 */
public record K8sAutomationRequestContext(ClientUserDetails user, List<CloudAccount> accounts,
                                          KubernetesClusterConfig config) {

    /** Request attribute the interceptor stores the context under (a literal, so annotations can name it). */
    public static final String ATTRIBUTE = "com.xammer.cloud.security.K8sAutomationRequestContext";

    public K8sAutomationRequestContext {
        accounts = List.copyOf(accounts);
    }

    /** The entitled row whose DB id owns the config, if any (legacy configs have none). */
    public Optional<CloudAccount> owningAccount() {
        if (config == null || config.getCloudAccountId() == null) {
            return Optional.empty();
        }
        return accounts.stream().filter(a -> config.getCloudAccountId().equals(a.getId())).findFirst();
    }

    /** The owning row, else the first entitled row. */
    public CloudAccount primaryAccount() {
        return owningAccount().orElse(accounts.isEmpty() ? null : accounts.get(0));
    }

    /**
     * Rows the account gates are evaluated over: the owning row alone, else every entitled row,
     * so the stricter one wins (design §6.6 step 4).
     */
    public List<CloudAccount> gateAccounts() {
        return owningAccount().map(List::of).orElse(accounts);
    }
}
