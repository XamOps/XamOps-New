package com.xammer.cloud.repository;

import com.xammer.cloud.domain.MarketplaceAccount;
import com.xammer.cloud.domain.MarketplacePayerAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MarketplaceAccountRepository extends JpaRepository<MarketplaceAccount, Long> {

    List<MarketplaceAccount> findByPayerAccount(MarketplacePayerAccount payerAccount);

    Optional<MarketplaceAccount> findByAwsAccountId(String awsAccountId);

    List<MarketplaceAccount> findByBillingExtractedFalse();
}
