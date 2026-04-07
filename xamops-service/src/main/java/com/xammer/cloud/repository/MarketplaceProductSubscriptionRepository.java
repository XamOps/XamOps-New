package com.xammer.cloud.repository;

import com.xammer.cloud.domain.MarketplaceProductSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MarketplaceProductSubscriptionRepository extends JpaRepository<MarketplaceProductSubscription, Long> {

    List<MarketplaceProductSubscription> findByCloudAccountId(String cloudAccountId);

    void deleteByCloudAccountId(String cloudAccountId);
}
