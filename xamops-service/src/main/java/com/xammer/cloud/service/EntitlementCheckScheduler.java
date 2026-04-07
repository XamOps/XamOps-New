package com.xammer.cloud.service;

import com.xammer.cloud.domain.IsvMarketplaceCustomer;
import com.xammer.cloud.repository.IsvMarketplaceCustomerRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Nightly scheduler that identifies ACTIVE customers who haven't had an entitlement
 * event (subscribe-success, entitlement-updated) in the past 30 days.
 *
 * For SaaS subscription products, AWS sends SNS events for all lifecycle changes,
 * so customers that have gone 30+ days without any entitlement signal are flagged.
 *
 * Conservative approach: stale customers are logged and their lastEntitlementCheck
 * is NOT reset — the ISV can review and manually cancel if needed. Automatic
 * expiry only happens via:
 *   1. SNS unsubscribe-success events (Phase 2)
 *   2. AWS BatchMeterUsage returning CustomerNotSubscribed (Phase 3A)
 */
@Service
@Slf4j
public class EntitlementCheckScheduler {

    /** Days since last entitlement signal before a customer is considered stale. */
    private static final int STALE_DAYS = 30;

    private final IsvMarketplaceCustomerRepository customerRepository;

    public EntitlementCheckScheduler(IsvMarketplaceCustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    /**
     * Runs nightly at 02:00 AM UTC.
     * Logs ACTIVE customers with no entitlement signal in the past 30 days.
     */
    @Scheduled(cron = "0 0 2 * * *")
    @Transactional(readOnly = true)
    public void checkStaleEntitlements() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(STALE_DAYS);
        List<IsvMarketplaceCustomer> stale =
                customerRepository.findActiveCustomersNotCheckedSince(cutoff);

        if (stale.isEmpty()) {
            log.info("EntitlementCheckScheduler: all active customers have recent entitlement signals");
            return;
        }

        log.warn("EntitlementCheckScheduler: {} ACTIVE customer(s) have had no entitlement signal in {}+ days",
                stale.size(), STALE_DAYS);

        for (IsvMarketplaceCustomer c : stale) {
            log.warn("  Stale customer: product={} customerId={} registeredAt={} lastCheck={}",
                    c.getProductCode(),
                    c.getCustomerIdentifier(),
                    c.getRegisteredAt(),
                    c.getLastEntitlementCheck() != null ? c.getLastEntitlementCheck() : "never");
        }
    }
}
