package com.xammer.billops.repository;

import com.xammer.billops.domain.CloudAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CloudAccountRepository extends JpaRepository<CloudAccount, Long> {

    List<CloudAccount> findByClientId(Long clientId);

    // MODIFIED: Return a list to handle multiple accounts
    List<CloudAccount> findByAwsAccountId(String awsAccountId);
    List<CloudAccount> findByAwsAccountIdIn(List<String> awsAccountIds);

}