package com.xammer.cloud.repository;

import com.xammer.cloud.domain.MarketplaceSubscriptionEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MarketplaceSubscriptionEventRepository
        extends JpaRepository<MarketplaceSubscriptionEvent, Long> {

    /** Recent events for a product — ordered newest first. */
    List<MarketplaceSubscriptionEvent> findByProductCodeOrderByReceivedAtDesc(
            String productCode, Pageable pageable);

    /** All events for a specific customer within a product. */
    List<MarketplaceSubscriptionEvent> findByProductCodeAndCustomerIdentifierOrderByReceivedAtDesc(
            String productCode, String customerIdentifier);
}
