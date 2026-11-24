package com.xammer.cloud.repository;

import com.xammer.cloud.domain.CloudAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface CloudAccountRepository extends JpaRepository<CloudAccount, Long> {

    Optional<CloudAccount> findByGcpProjectId(String gcpProjectId);
    boolean existsByGcpProjectId(String gcpProjectId);
    Optional<CloudAccount> findByExternalId(String externalId);

    /**
     * The reseller billing config for a CSP-managed GCP project, or empty when the project
     * is not CSP-managed / has no config linked.
     * <p>
     * {@code CloudAccount.cspBillingConfig} is a LAZY {@code @ManyToOne}, so reading it off
     * a detached account outside a transaction throws LazyInitializationException. Selecting
     * the association directly sidesteps that, which matters because the cost getters run on
     * background executor threads with no open session.
     */
    @Query("SELECT a.cspBillingConfig FROM CloudAccount a "
         + "WHERE a.gcpProjectId = :gcpProjectId AND a.cspManaged = true "
         + "AND a.cspBillingConfig IS NOT NULL")
    Optional<com.xammer.cloud.domain.CspBillingConfig> findCspBillingConfigByGcpProjectId(
            @Param("gcpProjectId") String gcpProjectId);

    /**
     * Records whether a GCP project's billing source actually produced data.
     * <p>
     * A targeted UPDATE rather than load-mutate-save for the same reason
     * {@link #findCspBillingConfigByGcpProjectId} selects the association directly: this is
     * called from the cost path, which runs on background executor threads with no open
     * session, and merging a detached {@code CloudAccount} there would touch its LAZY
     * {@code cspBillingConfig}. A single statement cannot.
     */
    @Modifying
    @Transactional
    @Query("UPDATE CloudAccount a SET a.billingDataAvailable = :available "
         + "WHERE a.gcpProjectId = :gcpProjectId")
    int updateBillingDataAvailable(@Param("gcpProjectId") String gcpProjectId,
                                   @Param("available") boolean available);

    // MODIFIED: Changed return type to List<CloudAccount>
    List<CloudAccount> findByAwsAccountId(String awsAccountId);

    /** Accounts served by one CUR — many when the report belongs to a XamOps MPA. */
    List<CloudAccount> findByCurSourceId(Long curSourceId);

    // ── Invoice-only accounts ───────────────────────────────────────────────────
    //
    // billops creates an account with status BILLING_ONLY for an uploaded AWS bill, purely so
    // the invoice has something to point at. It has no role and no credentials, and it must
    // appear on billops' Billing pages only. The list queries here (findAll, findByClientId,
    // findByClientIdIn, findByProvider) leave those rows out, which covers every account list,
    // dashboard and scheduled job in this service without editing each caller. Lookups by
    // account id are unchanged. coalesce(): a plain <> would drop any row whose status is NULL.

    @Query("select a from CloudAccount a where a.client.id = :clientId and coalesce(a.status, '') <> 'BILLING_ONLY'")
    List<CloudAccount> findByClientId(@Param("clientId") Long clientId);

    /** Cheap guard for client deletion — how many cloud accounts are attached to a client. */
    long countByClientId(Long clientId);

    @Override
    @Query("select a from CloudAccount a where coalesce(a.status, '') <> 'BILLING_ONLY'")
    List<CloudAccount> findAll();

    @Query("select a from CloudAccount a where a.client.id in :clientIds and coalesce(a.status, '') <> 'BILLING_ONLY'")
    List<CloudAccount> findByClientIdIn(@Param("clientIds") List<Long> clientIds);

    Optional<CloudAccount> findByAwsAccountIdAndClientId(String awsAccountId, Long clientId);

    // MODIFIED: This was changed in the previous step and remains correct
    List<CloudAccount> findByAwsAccountIdOrGcpProjectId(String awsAccountId, String gcpProjectId);

    @Query("SELECT ca FROM CloudAccount ca WHERE ca.awsAccountId = :accountId OR ca.gcpProjectId = :accountId OR ca.azureSubscriptionId = :accountId")
    Optional<CloudAccount> findByProviderAccountId(@Param("accountId") String accountId);

    /** Resolves an agent's account-scoped API key to the account that owns it. */
    Optional<CloudAccount> findByAgentApiKey(String agentApiKey);

    boolean existsByAgentApiKey(String agentApiKey);

    Optional<CloudAccount> findByAzureSubscriptionId(String subscriptionId);

    Optional<CloudAccount> findByGcpBillingAccountId(String gcpBillingAccountId);
    Optional<CloudAccount> findByAwsAccountIdOrGcpProjectIdOrAzureSubscriptionId(String awsAccountId, String gcpProjectId, String azureSubscriptionId);

    @Query("select a from CloudAccount a where a.provider = :provider and coalesce(a.status, '') <> 'BILLING_ONLY'")
    List<CloudAccount> findByProvider(@Param("provider") String provider);

    List<CloudAccount> findByAwsAccountIdIn(List<String> awsAccountIds);

    List<CloudAccount> findByCspManagedTrue();

    boolean existsByCspBillingConfigId(Long cspBillingConfigId);

}
