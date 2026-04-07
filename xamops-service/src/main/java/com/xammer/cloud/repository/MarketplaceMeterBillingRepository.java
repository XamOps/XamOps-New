package com.xammer.cloud.repository;

import com.xammer.cloud.domain.MarketplaceMeterBilling;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MarketplaceMeterBillingRepository extends JpaRepository<MarketplaceMeterBilling, Long> {

    List<MarketplaceMeterBilling> findByCloudAccountIdAndProductCode(String cloudAccountId, String productCode);

    List<MarketplaceMeterBilling> findByCloudAccountId(String cloudAccountId);

    void deleteByCloudAccountId(String cloudAccountId);
}
