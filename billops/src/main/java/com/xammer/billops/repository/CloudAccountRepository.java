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

    List<CloudAccount> findByClientId(Long clientId);

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
    @Query("select a from CloudAccount a left join fetch a.client")
    List<CloudAccount> findAllWithClient();

}