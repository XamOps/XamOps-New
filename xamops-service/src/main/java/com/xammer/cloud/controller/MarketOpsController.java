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

        // Build per-product cost lookup from billing table (exact product code match)
        List<com.xammer.cloud.domain.MarketplaceMeterBilling> allBilling =
                marketOpsService.getMeterBillingAll(cloudAccountId);

        Map<String, BigDecimal> totalCostByProduct = new HashMap<>();
        allBilling.forEach(entry ->
                totalCostByProduct.merge(entry.getProductCode(), entry.getCostUsd(), BigDecimal::add));

        // Check whether any subscription has a direct billing match.
        // When the Agreement API returned product codes (e.g. "prod-xxx") but Cost Explorer
        // stored billing under usage-type slugs (e.g. "software-usage-contracts"), none of
        // the per-product lookups will match.  In that case we expose the account-level total
        // so the UI can display meaningful cost information.
        boolean anyDirectMatch = subs.stream()
                .anyMatch(s -> totalCostByProduct.getOrDefault(s.getProductCode(), BigDecimal.ZERO)
                        .compareTo(BigDecimal.ZERO) > 0);

        BigDecimal accountTotalCostUsd = allBilling.stream()
                .map(com.xammer.cloud.domain.MarketplaceMeterBilling::getCostUsd)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

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
            // Included when per-product attribution is not available (shared billing scenario)
            if (!anyDirectMatch && accountTotalCostUsd.compareTo(BigDecimal.ZERO) > 0) {
                dto.put("accountTotalCostUsd", accountTotalCostUsd);
            }
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
    public ResponseEntity<Map<String, Object>> getMeterBilling(
            @PathVariable String productCode,
            @RequestParam String cloudAccountId) {

        List<MarketplaceMeterBilling> entries =
                marketOpsService.getMeterBilling(cloudAccountId, productCode);

        // When no product-specific billing exists (common for accounts with multiple
        // marketplace subscriptions — AWS CE groups charges under shared usage-type slugs
        // making per-product attribution impossible), fall back to account-level billing so
        // the user can at least see their total marketplace spend.
        boolean accountLevel = false;
        if (entries.isEmpty()) {
            entries = marketOpsService.getMeterBillingAll(cloudAccountId)
                    .stream()
                    .sorted(java.util.Comparator.comparing(MarketplaceMeterBilling::getYearMonth).reversed())
                    .collect(Collectors.toList());
            accountLevel = true;
        }

        List<Map<String, Object>> rows = entries.stream().map(entry -> {
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("yearMonth", entry.getYearMonth());
            dto.put("usageType", entry.getUsageType());
            dto.put("usageQuantity", entry.getUsageQuantity());
            dto.put("costUsd", entry.getCostUsd());
            return dto;
        }).collect(Collectors.toList());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("entries", rows);
        response.put("accountLevel", accountLevel);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/marketops/subscriptions/{productCode}/billing/daily
     *     ?cloudAccountId={awsAccountId}&yearMonth={yyyy-MM}
     *
     * Returns daily meter billing for a specific product within a calendar month.
     * Queries AWS Cost Explorer with DAILY granularity (live call, not cached).
     *
     * Response shape:
     * [
     *   { "date": "2026-03-01", "costUsd": 2.10, "usageQuantity": 23.5 },
     *   ... one entry per calendar day, zero-cost days included
     * ]
     */
    @GetMapping("/subscriptions/{productCode}/billing/daily")
    public ResponseEntity<Map<String, Object>> getDailyMeterBilling(
            @PathVariable String productCode,
            @RequestParam String cloudAccountId,
            @RequestParam String yearMonth) {

        CloudAccount account = resolveAccount(cloudAccountId);
        List<Map<String, Object>> entries = marketOpsService.getDailyMeterBilling(account, productCode, yearMonth);

        // Same account-level fallback as monthly: when the product code is an agreement ID
        // that does not match any stored usage types, the daily query returns all-zero days.
        // In that case re-run the query without a product filter (account-level).
        boolean accountLevel = false;
        boolean allZero = entries.stream()
                .allMatch(e -> {
                    Object cost = e.get("costUsd");
                    if (cost == null) return true;
                    if (cost instanceof java.math.BigDecimal) return ((java.math.BigDecimal) cost).compareTo(java.math.BigDecimal.ZERO) == 0;
                    return Double.parseDouble(cost.toString()) == 0.0;
                });

        if (allZero) {
            entries = marketOpsService.getDailyMeterBillingAccountLevel(account, yearMonth);
            accountLevel = true;
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("entries", entries);
        response.put("accountLevel", accountLevel);
        return ResponseEntity.ok(response);
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
        // force=true: user-triggered syncs always bypass the 24-hour freshness cache
        Map<String, Object> result = marketOpsService.syncAll(account, true);
        return ResponseEntity.ok(result);
    }

    // ------------------------------------------------------------------

    private CloudAccount resolveAccount(String cloudAccountId) {
        return cloudAccountRepository.findByProviderAccountId(cloudAccountId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No cloud account found for ID: " + cloudAccountId));
    }
}
