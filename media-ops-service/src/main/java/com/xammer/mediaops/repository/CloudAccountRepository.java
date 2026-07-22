package com.xammer.mediaops.repository;

import com.xammer.mediaops.domain.CloudAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CloudAccountRepository extends JpaRepository<CloudAccount, Long> {

    /**
     * Look up a connected AWS account by its account number, scoped to the owning
     * client so one tenant can never target another tenant's account.
     */
    Optional<CloudAccount> findByAwsAccountIdAndClientId(String awsAccountId, Long clientId);
}
