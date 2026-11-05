package com.xammer.billops.repository;

import com.xammer.billops.domain.Client;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ClientRepository extends JpaRepository<Client, Long> {

    /**
     * Every client except the ones that exist only because of uploaded bills: a client with
     * at least one invoice-only account and no other account (see
     * {@link CloudAccountRepository#findInvoiceOnlyClientIds()}).
     *
     * <p>Every client list, picker and dashboard calls this, so overriding it here keeps those
     * clients on the Billing pages only. A client with no accounts at all is still listed: a
     * freshly created client has none yet. Lookups by id or name still find hidden clients.
     */
    @Override
    @Query("select c from Client c where not (exists (select 1 from CloudAccount a where a.client = c and a.status = 'BILLING_ONLY') "
            + "and not exists (select 1 from CloudAccount b where b.client = c and coalesce(b.status, '') <> 'BILLING_ONLY'))")
    List<Client> findAll();

    // Find client by name
    Client findByName(String name);

    // Query to get the maximum client ID for auto-increment
    @Query("SELECT MAX(c.id) FROM Client c")
    Long findMaxId();

    /**
     * Returns clients owned by a specific ROLE_BILLOPS_ADMIN_SUB user.
     * Used to scope the support dashboard for sub-admins.
     */
    List<Client> findByOwnerAdminId(Long ownerAdminId);

    /** All self-signup trial clients (for the admin trial-management view). */
    List<Client> findByPlanType(String planType);

    /**
     * Current trials plus clients that were once on a trial (trial_status set, e.g. CONVERTED).
     * Mirrors the operator audit query (plan_type = 'TRIAL' OR trial_status IS NOT NULL) so the
     * admin view can show converted tenants without listing every enterprise PAID client.
     */
    @Query("SELECT c FROM Client c WHERE c.planType = :planType OR c.trialStatus IS NOT NULL")
    List<Client> findTrialOrFormerTrial(@Param("planType") String planType);
}