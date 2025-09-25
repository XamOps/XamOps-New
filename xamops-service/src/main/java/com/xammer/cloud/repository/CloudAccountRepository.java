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

    // MODIFIED: Changed return type to List<CloudAccount>
    List<CloudAccount> findByAwsAccountId(String awsAccountId);

    List<CloudAccount> findByClientId(Long clientId);

    Optional<CloudAccount> findByAwsAccountIdAndClientId(String awsAccountId, Long clientId);

    // MODIFIED: This was changed in the previous step and remains correct
    List<CloudAccount> findByAwsAccountIdOrGcpProjectId(String awsAccountId, String gcpProjectId);

    @Query("SELECT ca FROM CloudAccount ca WHERE ca.awsAccountId = :accountId OR ca.gcpProjectId = :accountId OR ca.azureSubscriptionId = :accountId")
    Optional<CloudAccount> findByProviderAccountId(@Param("accountId") String accountId);

    Optional<CloudAccount> findByAzureSubscriptionId(String subscriptionId);

}