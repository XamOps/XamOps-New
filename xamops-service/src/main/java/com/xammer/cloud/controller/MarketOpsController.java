package com.xammer.cloud.controller;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.MarketplaceMeterBilling;
import com.xammer.cloud.domain.MarketplaceProductSubscription;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.MarketOpsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/marketops")
@PreAuthorize("hasAuthority('ROLE_XAMOPS') or hasAuthority('ROLE_SUPER_ADMIN')")
@Slf4j
public class MarketOpsController {

    private final MarketOpsService marketOpsService;
    private final CloudAccountRepository cloudAccountRepository;

    public MarketOpsController(MarketOpsService marketOpsService,
                                CloudAccountRepository cloudAccountRepository) {
        this.marketOpsService = marketOpsService;
        this.cloudAccountRepository = cloudAccountRepository;
    }

    /**
     * GET /api/marketops/subscriptions?cloudAccountId={awsAccountId}
     *
     * Returns subscribed marketplace products for the given AWS account.
     * Triggers a sync from AWS on first call (no data in DB).
     *
     * Response shape:
     * [
     *   {
     *     "id": 1,
     *     "productCode": "prod-xxx",
     *     "productTitle": "uniskai",
     *     "vendorName": "...",
     *     "subscriptionStatus": "ACTIVE",
     *     "expiryDate": "2027-01-01",
     *     "totalCostUsd": 186.50
     *   },
     *   ...
     * ]
     */
    @GetMapping("/subscriptions")
    public ResponseEntity<List<Map<String, Object>>> getSubscriptions(
            @RequestParam String cloudAccountId) {

        CloudAccount account = resolveAccount(cloudAccountId);

        List<MarketplaceProductSubscription> subs = marketOpsService.getSubscriptions(account);

        // Compute totalCostUsd per productCode from billing table
        Map<String, BigDecimal> totalCostByProduct = new HashMap<>();
        marketOpsService.getMeterBillingAll(cloudAccountId).forEach(entry ->
                totalCostByProduct.merge(entry.getProductCode(), entry.getCostUsd(), BigDecimal::add));

        List<Map<String, Object>> result = subs.stream().map(sub -> {
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("id", sub.getId());
            dto.put("productCode", sub.getProductCode());
            dto.put("productTitle", sub.getProductTitle() != null ? sub.getProductTitle() : sub.getProductCode());
            dto.put("vendorName", sub.getVendorName());
            dto.put("subscriptionStatus", sub.getSubscriptionStatus());
            dto.put("expiryDate", sub.getExpiryDate() != null ? sub.getExpiryDate().toString() : null);
            dto.put("totalCostUsd", totalCostByProduct.getOrDefault(sub.getProductCode(), BigDecimal.ZERO));
            dto.put("syncedAt", sub.getSyncedAt().toString());
            return dto;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/marketops/subscriptions/{productCode}/billing?cloudAccountId={awsAccountId}
     *
     * Returns monthly meter billing entries for a specific product on an account.
     * Sorted newest-first.
     *
     * Response shape:
     * [
     *   {
     *     "yearMonth": "2026-03",
     *     "usageType": "...",
     *     "usageQuantity": 720.0,
     *     "costUsd": 62.40
     *   },
     *   ...
     * ]
     */
    @GetMapping("/subscriptions/{productCode}/billing")
    public ResponseEntity<List<Map<String, Object>>> getMeterBilling(
            @PathVariable String productCode,
            @RequestParam String cloudAccountId) {

        List<MarketplaceMeterBilling> entries =
                marketOpsService.getMeterBilling(cloudAccountId, productCode);

        List<Map<String, Object>> result = entries.stream().map(entry -> {
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("yearMonth", entry.getYearMonth());
            dto.put("usageType", entry.getUsageType());
            dto.put("usageQuantity", entry.getUsageQuantity());
            dto.put("costUsd", entry.getCostUsd());
            return dto;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    /**
     * POST /api/marketops/sync?cloudAccountId={awsAccountId}
     *
     * Forces a full re-sync from AWS Marketplace Entitlement + Cost Explorer.
     *
     * Response shape:
     * {
     *   "status": "SYNCED",
     *   "cloudAccountId": "123456789012",
     *   "subscriptionsFound": 3,
     *   "billingMonthsSynced": 12,
     *   "syncedAt": "2026-04-01T10:00:00"
     * }
     */
    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> sync(@RequestParam String cloudAccountId) {
        log.info("Manual sync triggered for account {}", cloudAccountId);
        CloudAccount account = resolveAccount(cloudAccountId);
        Map<String, Object> result = marketOpsService.syncAll(account);
        return ResponseEntity.ok(result);
    }

    // ------------------------------------------------------------------

    private CloudAccount resolveAccount(String cloudAccountId) {
        return cloudAccountRepository.findByProviderAccountId(cloudAccountId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No cloud account found for ID: " + cloudAccountId));
    }
}
