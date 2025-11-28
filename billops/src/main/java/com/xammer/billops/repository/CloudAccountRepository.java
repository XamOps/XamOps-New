package com.xammer.billops.repository;

import com.xammer.billops.domain.CloudAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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

}