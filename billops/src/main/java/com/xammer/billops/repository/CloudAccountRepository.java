package com.xammer.billops.repository;

import com.xammer.billops.domain.CloudAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface CloudAccountRepository extends JpaRepository<CloudAccount, Long> {

    // ── Invoice-only accounts ───────────────────────────────────────────────────
    //
    // An account created from an uploaded AWS bill (status BILLING_ONLY, see
    // CloudAccount.STATUS_INVOICE_ONLY) exists so the invoice has something to point at, and
    // nothing else. It must show on the Billing pages only.
    //
    // So the list queries below leave those rows out: findAll() and findByClientId() are the
    // calls every account list, picker, dashboard and background job makes, and excluding
    // them here covers all of those without touching each caller. Lookups by id, such as
    // findByAwsAccountId, still find an invoice-only account, which is what the upload and
    // invoice code needs. The *IncludingInvoiceOnly methods are for the Billing pages.
    //
    // coalesce(): status is NOT NULL on new rows, but a plain <> would silently drop any
    // older row whose status is NULL.

    @Override
    @Query("select a from CloudAccount a where coalesce(a.status, '') <> 'BILLING_ONLY'")
    List<CloudAccount> findAll();

    @Query("select a from CloudAccount a")
    List<CloudAccount> findAllIncludingInvoiceOnly();

    @Query("select a from CloudAccount a where a.client.id = :clientId and coalesce(a.status, '') <> 'BILLING_ONLY'")
    List<CloudAccount> findByClientId(@Param("clientId") Long clientId);

    @Query("select a from CloudAccount a where a.client.id = :clientId")
    List<CloudAccount> findByClientIdIncludingInvoiceOnly(@Param("clientId") Long clientId);

    /**
     * Clients that exist only because of uploaded bills: they have at least one invoice-only
     * account and no other. Derived rather than flagged, so a client that later gets a real
     * account stops being hidden by itself, and clients created before this rule existed
     * need no migration.
     */
    @Query("select distinct a.client.id from CloudAccount a where a.status = 'BILLING_ONLY' and a.client is not null "
            + "and not exists (select 1 from CloudAccount b where b.client = a.client and coalesce(b.status, '') <> 'BILLING_ONLY')")
    List<Long> findInvoiceOnlyClientIds();

    /** Accounts of one client, without loading them — the delete-client guard only needs the count. */
    long countByClientId(Long clientId);

    /**
     * Cloud-account count per client as {@code [clientId, count]} rows, for the admin client list.
     * Clients with no accounts are absent from the result, so callers must default them to 0.
     */
    @Query("SELECT ca.client.id, COUNT(ca) FROM CloudAccount ca WHERE ca.client IS NOT NULL "
            + "AND coalesce(ca.status, '') <> 'BILLING_ONLY' GROUP BY ca.client.id")
    List<Object[]> countAccountsGroupedByClient();

    // --- ADDED: Required for Account Verification Flow ---
    Optional<CloudAccount> findByExternalId(String externalId);

    @Query("SELECT ca FROM CloudAccount ca WHERE LOWER(TRIM(ca.gcpProjectId)) = LOWER(:gcpProjectId)")
    Optional<CloudAccount> findByGcpProjectId(@Param("gcpProjectId") String gcpProjectId);

    @Query("SELECT ca FROM CloudAccount ca WHERE LOWER(TRIM(ca.awsAccountId)) = LOWER(:awsAccountId)")
    List<CloudAccount> findByAwsAccountId(@Param("awsAccountId") String awsAccountId);

    List<CloudAccount> findByAwsAccountIdIn(List<String> awsAccountIds);

    // --- AZURE SUPPORT ---
    Optional<CloudAccount> findByAzureSubscriptionId(String azureSubscriptionId);

    // --- ADDED: Missing Method for Monitoring Update ---
    Optional<CloudAccount> findByAwsAccountIdOrGcpProjectIdOrAzureSubscriptionId(String awsAccountId, String gcpProjectId, String azureSubscriptionId);

    @Query("SELECT ca FROM CloudAccount ca WHERE ca.awsAccountId IN :ids OR ca.gcpProjectId IN :ids OR ca.azureSubscriptionId IN :ids")
    List<CloudAccount> findByAnyCloudIdIn(@Param("ids") java.util.Collection<String> ids);

    // Admin dashboard: every account with its client loaded, so the build can read client names
    // after its own short read transactions end (or after a failed read cleared an open-in-view
    // EntityManager) without touching a lazy proxy.
    @Transactional(readOnly = true)
    @Query("select a from CloudAccount a left join fetch a.client where coalesce(a.status, '') <> 'BILLING_ONLY'")
    List<CloudAccount> findAllWithClient();

}