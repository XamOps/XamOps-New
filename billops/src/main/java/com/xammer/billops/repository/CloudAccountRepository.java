package com.xammer.billops.repository;

import com.xammer.billops.domain.CloudAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CloudAccountRepository extends JpaRepository<CloudAccount, Long> {

    List<CloudAccount> findByClientId(Long clientId);

    // ADDED: Method to find by AWS Account ID
    Optional<CloudAccount> findByAwsAccountId(String awsAccountId);

    // Alternative methods depending on your CloudAccount field names:
    // Optional<CloudAccount> findByAccountNumber(String accountNumber);
    // Optional<CloudAccount> findByLinkedAccountId(String linkedAccountId);
}
