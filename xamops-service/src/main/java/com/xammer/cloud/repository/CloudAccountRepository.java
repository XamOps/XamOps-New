package com.xammer.cloud.repository;

import com.xammer.cloud.domain.CloudAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface CloudAccountRepository extends JpaRepository<CloudAccount, Long> {

    Optional<CloudAccount> findByGcpProjectId(String gcpProjectId);

    Optional<CloudAccount> findByExternalId(String externalId);

    // MODIFIED: Changed return type to List<CloudAccount>
    List<CloudAccount> findByAwsAccountId(String awsAccountId);

    // ✅ FIXED: Using custom query to avoid relationship traversal issues
    @Query("SELECT ca FROM CloudAccount ca WHERE ca.client.id = :clientId")
    List<CloudAccount> findByClientId(@Param("clientId") Long clientId);

    Optional<CloudAccount> findByAwsAccountIdAndClient_Id(String awsAccountId, Long clientId);

    // MODIFIED: This was changed in the previous step and remains correct
    List<CloudAccount> findByAwsAccountIdOrGcpProjectId(String awsAccountId, String gcpProjectId);

    @Query("SELECT ca FROM CloudAccount ca WHERE ca.awsAccountId = :accountId OR ca.gcpProjectId = :accountId OR ca.azureSubscriptionId = :accountId")
    Optional<CloudAccount> findByProviderAccountId(@Param("accountId") String accountId);

    Optional<CloudAccount> findByAzureSubscriptionId(String subscriptionId);

    Optional<CloudAccount> findByGcpBillingAccountId(String gcpBillingAccountId);

    Optional<CloudAccount> findByAwsAccountIdOrGcpProjectIdOrAzureSubscriptionId(
            String awsAccountId, String gcpProjectId, String azureSubscriptionId);

    // ==================== CLOUDIDR INTEGRATION METHODS ====================

    /**
     * Update CloudFormation stack ID and status
     */
    @Transactional
    @Modifying
    @Query("UPDATE CloudAccount ca SET ca.cfStackId = :stackId, ca.cfStackStatus = :status " +
            "WHERE ca.awsAccountId = :awsAccountId")
    void updateCloudFormationStackId(@Param("awsAccountId") String awsAccountId,
            @Param("stackId") String stackId,
            @Param("status") String status);

    /**
     * Update CloudFormation stack status only
     */
    @Transactional
    @Modifying
    @Query("UPDATE CloudAccount ca SET ca.cfStackStatus = :status " +
            "WHERE ca.awsAccountId = :awsAccountId")
    void updateCloudFormationStackStatus(@Param("awsAccountId") String awsAccountId,
            @Param("status") String status);

    /**
     * Get CloudFormation stack ID for an account
     */
    @Query("SELECT ca.cfStackId FROM CloudAccount ca WHERE ca.awsAccountId = :awsAccountId")
    String getCloudFormationStackId(@Param("awsAccountId") String awsAccountId);

    /**
     * Get CloudFormation stack status for an account
     */
    @Query("SELECT ca.cfStackStatus FROM CloudAccount ca WHERE ca.awsAccountId = :awsAccountId")
    String getCloudFormationStackStatus(@Param("awsAccountId") String awsAccountId);

    /**
     * Get default region for an account
     */
    @Query("SELECT ca.defaultRegion FROM CloudAccount ca WHERE ca.awsAccountId = :awsAccountId")
    String getDefaultRegion(@Param("awsAccountId") String awsAccountId);

    /**
     * Update CloudIDR account linking information
     */
    @Transactional
    @Modifying
    @Query("UPDATE CloudAccount ca SET ca.cloudidrCspAccountId = :cspAccountId, " +
            "ca.cloudidrRoleArn = :roleArn, ca.cloudidrStatus = :status, " +
            "ca.cloudidrLinkedAt = :linkedAt " +
            "WHERE ca.awsAccountId = :awsAccountId")
    void updateCloudIdrLink(@Param("awsAccountId") String awsAccountId,
            @Param("cspAccountId") String cspAccountId,
            @Param("roleArn") String roleArn,
            @Param("status") String status,
            @Param("linkedAt") Instant linkedAt);

    /**
     * Get CloudIDR CSP Account ID
     */
    @Query("SELECT ca.cloudidrCspAccountId FROM CloudAccount ca WHERE ca.awsAccountId = :awsAccountId")
    String getCloudIdrCspId(@Param("awsAccountId") String awsAccountId);

    /**
     * Get CloudIDR Role ARN
     */
    @Query("SELECT ca.cloudidrRoleArn FROM CloudAccount ca WHERE ca.awsAccountId = :awsAccountId")
    String getCloudIdrRoleArn(@Param("awsAccountId") String awsAccountId);

    /**
     * Get CloudIDR link status
     */
    @Query("SELECT ca.cloudidrStatus FROM CloudAccount ca WHERE ca.awsAccountId = :awsAccountId")
    String getCloudIdrStatus(@Param("awsAccountId") String awsAccountId);

    /**
     * Find all CloudIDR-linked accounts
     */
    @Query("SELECT ca FROM CloudAccount ca WHERE ca.cloudidrCspAccountId IS NOT NULL AND ca.cloudidrStatus = 'LINKED'")
    List<CloudAccount> findAllCloudIdrLinkedAccounts();

    /**
     * Check if account is linked to CloudIDR
     */
    @Query("SELECT CASE WHEN COUNT(ca) > 0 THEN true ELSE false END FROM CloudAccount ca " +
            "WHERE ca.awsAccountId = :awsAccountId AND ca.cloudidrCspAccountId IS NOT NULL AND ca.cloudidrStatus = 'LINKED'")
    boolean isAccountLinkedToCloudIdr(@Param("awsAccountId") String awsAccountId);

    /**
     * Get CloudAccount by CSP Account ID (reverse lookup)
     */
    @Query("SELECT ca FROM CloudAccount ca WHERE ca.cloudidrCspAccountId = :cspAccountId")
    Optional<CloudAccount> findByCloudidrCspAccountId(@Param("cspAccountId") String cspAccountId);

    /**
     * Find AWS accounts for a client
     */
    @Query("SELECT ca FROM CloudAccount ca WHERE ca.client.id = :clientId AND ca.provider = 'AWS' AND ca.awsAccountId IS NOT NULL ORDER BY ca.id ASC")
    List<CloudAccount> findAwsAccountsByClientId(@Param("clientId") Long clientId);
}
