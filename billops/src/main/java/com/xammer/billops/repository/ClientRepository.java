package com.xammer.billops.repository;

import com.xammer.billops.domain.Client;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ClientRepository extends JpaRepository<Client, Long> {
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