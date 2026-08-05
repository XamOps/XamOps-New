package com.xammer.cloud.repository;

import com.xammer.cloud.domain.CloudAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

@Repository
public interface CloudAccountRepository extends JpaRepository<CloudAccount, Long> {

    Optional<CloudAccount> findByGcpProjectId(String gcpProjectId);
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

    // MODIFIED: Changed return type to List<CloudAccount>
    List<CloudAccount> findByAwsAccountId(String awsAccountId);

    List<CloudAccount> findByClientId(Long clientId);

    List<CloudAccount> findByClientIdIn(List<Long> clientIds);

    Optional<CloudAccount> findByAwsAccountIdAndClientId(String awsAccountId, Long clientId);

    // MODIFIED: This was changed in the previous step and remains correct
    List<CloudAccount> findByAwsAccountIdOrGcpProjectId(String awsAccountId, String gcpProjectId);

    @Query("SELECT ca FROM CloudAccount ca WHERE ca.awsAccountId = :accountId OR ca.gcpProjectId = :accountId OR ca.azureSubscriptionId = :accountId")
    Optional<CloudAccount> findByProviderAccountId(@Param("accountId") String accountId);

    Optional<CloudAccount> findByAzureSubscriptionId(String subscriptionId);

    Optional<CloudAccount> findByGcpBillingAccountId(String gcpBillingAccountId);
    Optional<CloudAccount> findByAwsAccountIdOrGcpProjectIdOrAzureSubscriptionId(String awsAccountId, String gcpProjectId, String azureSubscriptionId);

    List<CloudAccount> findByProvider(String provider);

    List<CloudAccount> findByAwsAccountIdIn(List<String> awsAccountIds);

    List<CloudAccount> findByCspManagedTrue();

    boolean existsByCspBillingConfigId(Long cspBillingConfigId);

}
