package com.xammer.cloud.repository;

import com.xammer.cloud.domain.Client;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClientRepository extends JpaRepository<Client, Long> {

    /**
     * Every client except the ones billops made only to hold an uploaded AWS bill: a client
     * with at least one invoice-only account (status BILLING_ONLY) and no other account.
     * They belong on the Billing pages in billops and nowhere here, so every list, picker and
     * job that calls findAll() leaves them out. Derived from the accounts rather than
     * flagged, so a client that later gets a real account stops being hidden by itself.
     * A client with no accounts at all is still listed. Lookups by id or name still find
     * hidden clients.
     */
    @Override
    @Query("select c from Client c where not (exists (select 1 from CloudAccount a where a.client = c and a.status = 'BILLING_ONLY') "
            + "and not exists (select 1 from CloudAccount b where b.client = c and coalesce(b.status, '') <> 'BILLING_ONLY'))")
    List<Client> findAll();

    Optional<Client> findByName(String name);
    Optional<Client> findFirstByDemoTrue();
    List<Client> findByOwnerAdminId(Long ownerAdminId);

    List<Client> findByPlanType(String planType);
    List<Client> findByPlanTypeAndTrialStatus(String planType, String trialStatus);
}
