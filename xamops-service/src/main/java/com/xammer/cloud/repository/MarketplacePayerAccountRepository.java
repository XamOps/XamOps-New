package com.xammer.cloud.repository;

import com.xammer.cloud.domain.MarketplacePayerAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MarketplacePayerAccountRepository extends JpaRepository<MarketplacePayerAccount, Long> {

    Optional<MarketplacePayerAccount> findByPayerAwsAccountId(String payerAwsAccountId);

    boolean existsByPayerAwsAccountId(String payerAwsAccountId);
}
