package com.xammer.cloud.controller;

import com.xammer.cloud.dto.isv.IsvProductDto;
import com.xammer.cloud.dto.isv.IsvProductRequest;
import com.xammer.cloud.service.CustomerRegistrationService;
import com.xammer.cloud.service.IsvAnalyticsService;
import com.xammer.cloud.service.IsvOnboardingService;
import com.xammer.cloud.service.MeteringEngineService;
import com.xammer.cloud.service.SnsNotificationService;
import com.xammer.cloud.service.UsageEventService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * ISV Product Management API — XamOps Metering Platform
 *
 * Allows ISVs and admins to register/manage their AWS Marketplace products,
 * view customer registrations, metering records, and dashboard analytics.
 *
 * All endpoints require authentication. The manual trigger endpoint is
 * restricted to ROLE_SUPER_ADMIN only (see method-level @PreAuthorize below).
 */
@RestController
@RequestMapping("/api/isv")
@Slf4j
public class IsvProductController {

    private final IsvOnboardingService isvOnboardingService;
    private final CustomerRegistrationService customerRegistrationService;
    private final UsageEventService usageEventService;
    private final MeteringEngineService meteringEngineService;
    private final SnsNotificationService snsNotificationService;
    private final IsvAnalyticsService isvAnalyticsService;

    public IsvProductController(IsvOnboardingService isvOnboardingService,
                                 CustomerRegistrationService customerRegistrationService,
                                 UsageEventService usageEventService,
                                 MeteringEngineService meteringEngineService,
                                 SnsNotificationService snsNotificationService,
                                 IsvAnalyticsService isvAnalyticsService) {
        this.isvOnboardingService = isvOnboardingService;
        this.customerRegistrationService = customerRegistrationService;
        this.usageEventService = usageEventService;
        this.meteringEngineService = meteringEngineService;
        this.snsNotificationService = snsNotificationService;
        this.isvAnalyticsService = isvAnalyticsService;
    }

    // ─── Product CRUD ─────────────────────────────────────────────────────────

    /**
     * POST /api/isv/products
     * Register a new AWS Marketplace product for metering-as-a-service.
     *
     * Request:
     * {
     *   "productCode": "prod-xxxx",
     *   "productName": "My SaaS Product",
     *   "description": "...",
     *   "ownerEmail": "dev@mycompany.com",
     *   "roleArn": "arn:aws:iam::123456789012:role/XamOpsMeteringRole",
     *   "externalId": "optional-external-id",
     *   "dimensions": [
     *     { "dimensionKey": "api_calls", "description": "API calls made", "unit": "calls" },
     *     { "dimensionKey": "active_users", "description": "Active users", "unit": "users" }
     *   ]
     * }
     */
    @PostMapping("/products")
    public ResponseEntity<IsvProductDto> registerProduct(@RequestBody IsvProductRequest request) {
        log.info("ISV product registration request: code={} owner={}", request.getProductCode(), request.getOwnerEmail());
        IsvProductDto dto = isvOnboardingService.registerProduct(request);
        return ResponseEntity.ok(dto);
    }

    /**
     * GET /api/isv/products
     * List all registered ISV products (admin) or filter by ?ownerEmail=
     */
    @GetMapping("/products")
    public ResponseEntity<List<IsvProductDto>> listProducts(
            @RequestParam(required = false) String ownerEmail) {
        List<IsvProductDto> products = ownerEmail != null && !ownerEmail.isBlank()
                ? isvOnboardingService.getProductsByOwner(ownerEmail)
                : isvOnboardingService.getAllProducts();
        return ResponseEntity.ok(products);
    }

    /**
     * GET /api/isv/products/{productCode}
     * Get a single product with stats (active customers, pending events, etc.)
     */
    @GetMapping("/products/{productCode}")
    public ResponseEntity<IsvProductDto> getProduct(@PathVariable String productCode) {
        return ResponseEntity.ok(isvOnboardingService.getProduct(productCode));
    }

    /**
     * PUT /api/isv/products/{productCode}
     * Update product metadata (name, description, roleArn, dimensions).
     * productCode cannot be changed.
     */
    @PutMapping("/products/{productCode}")
    public ResponseEntity<IsvProductDto> updateProduct(
            @PathVariable String productCode,
            @RequestBody IsvProductRequest request) {
        request.setProductCode(productCode); // Ensure path takes precedence
        return ResponseEntity.ok(isvOnboardingService.updateProduct(productCode, request));
    }

    /**
     * DELETE /api/isv/products/{productCode}
     * Deactivate a product (does not delete history, just sets status=INACTIVE).
     * Deactivated products stop receiving events and scheduler skips them.
     */
    @DeleteMapping("/products/{productCode}")
    public ResponseEntity<IsvProductDto> deactivateProduct(@PathVariable String productCode) {
        log.info("Deactivating ISV product: {}", productCode);
        return ResponseEntity.ok(isvOnboardingService.deactivateProduct(productCode));
    }

    /**
     * POST /api/isv/products/{productCode}/regenerate-key
     * Regenerate the API key for the product. Old key is immediately invalidated.
     * Returns the new key — store it securely.
     */
    @PostMapping("/products/{productCode}/regenerate-key")
    public ResponseEntity<Map<String, String>> regenerateApiKey(@PathVariable String productCode) {
        log.info("API key regeneration for product: {}", productCode);
        IsvProductDto dto = isvOnboardingService.regenerateApiKey(productCode);
        return ResponseEntity.ok(Map.of(
                "productCode", productCode,
                "newApiKey", dto.getApiKey(),
                "message", "API key regenerated. Update your product configuration immediately."
        ));
    }

    // ─── Customer Management ──────────────────────────────────────────────────

    /**
     * GET /api/isv/products/{productCode}/customers
     * List all registered customers for a product (ACTIVE + CANCELLED).
     *
     * Response:
     * [
     *   {
     *     "id": 1,
     *     "customerIdentifier": "iQEMo...",
     *     "customerAwsAccountId": "123456789012",
     *     "status": "ACTIVE",
     *     "registeredAt": "2026-04-01T10:00:00",
     *     "cancelledAt": null
     *   }
     * ]
     */
    @GetMapping("/products/{productCode}/customers")
    public ResponseEntity<List<Map<String, Object>>> getCustomers(@PathVariable String productCode) {
        return ResponseEntity.ok(customerRegistrationService.getCustomers(productCode));
    }

    /**
     * DELETE /api/isv/products/{productCode}/customers/{customerId}
     * Manually cancel a customer (e.g. after receiving AWS SNS cancellation notification).
     */
    @DeleteMapping("/products/{productCode}/customers/{customerId}")
    public ResponseEntity<Map<String, String>> cancelCustomer(
            @PathVariable String productCode,
            @PathVariable String customerId) {
        customerRegistrationService.cancelCustomer(productCode, customerId);
        return ResponseEntity.ok(Map.of(
                "productCode", productCode,
                "customerId", customerId,
                "status", "CANCELLED"
        ));
    }

    // ─── Metering Records ─────────────────────────────────────────────────────

    /**
     * GET /api/isv/products/{productCode}/metering-records?limit=50
     * Returns recent metering records (submissions to AWS) for this product.
     *
     * Response:
     * [
     *   {
     *     "id": 101,
     *     "customerIdentifier": "iQEMo...",
     *     "dimension": "api_calls",
     *     "quantity": 15000,
     *     "usageTimestamp": "2026-04-07T10:00:00Z",
     *     "awsMeteringRecordId": "af123...",
     *     "status": "SUCCESS",
     *     "submittedAt": "2026-04-07T11:00:05",
     *     "errorMessage": null
     *   }
     * ]
     */
    @GetMapping("/products/{productCode}/metering-records")
    public ResponseEntity<List<Map<String, Object>>> getMeteringRecords(
            @PathVariable String productCode,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(meteringEngineService.getRecentMeteringRecords(productCode, Math.min(limit, 200)));
    }

    // ─── SNS Subscription Events ──────────────────────────────────────────────

    /**
     * GET /api/isv/products/{productCode}/events?limit=50
     * Returns recent AWS Marketplace SNS subscription lifecycle events for a product.
     *
     * Response:
     * [
     *   {
     *     "id": 1,
     *     "productCode": "prod-xxxx",
     *     "customerIdentifier": "iQEMo...",
     *     "eventType": "subscribe-success",
     *     "status": "PROCESSED",
     *     "errorMessage": null,
     *     "receivedAt": "2026-04-07T10:00:00Z",
     *     "processedAt": "2026-04-07T10:00:00.050Z"
     *   }
     * ]
     */
    @GetMapping("/products/{productCode}/events")
    public ResponseEntity<List<Map<String, Object>>> getSubscriptionEvents(
            @PathVariable String productCode,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(snsNotificationService.getRecentEvents(productCode, Math.min(limit, 200)));
    }

    // ─── Analytics ────────────────────────────────────────────────────────────

    /**
     * GET /api/isv/products/{productCode}/analytics
     * Returns historical analytics for a product:
     *   - customerGrowth:   new registrations per month
     *   - usageByDimension: total quantity reported per dimension per month
     *   - submissionHealth: SUCCESS vs FAILED metering record counts per month
     */
    @GetMapping("/products/{productCode}/analytics")
    public ResponseEntity<Map<String, Object>> getAnalytics(@PathVariable String productCode) {
        return ResponseEntity.ok(isvAnalyticsService.getAnalytics(productCode));
    }

    // ─── Dashboard ────────────────────────────────────────────────────────────

    /**
     * GET /api/isv/products/{productCode}/dashboard
     * Returns a consolidated dashboard view for a product:
     *   - Product details + dimensions
     *   - Live stats (customers, events, records)
     *   - Usage event summary (pending/failed counts)
     *   - Recent metering records (last 10)
     *
     * Response:
     * {
     *   "product": { ...IsvProductDto... },
     *   "eventStats": { "pendingEvents": 5, "failedEvents": 0 },
     *   "recentMeteringRecords": [ ...last 10 records... ]
     * }
     */
    @GetMapping("/products/{productCode}/dashboard")
    public ResponseEntity<Map<String, Object>> getDashboard(@PathVariable String productCode) {
        Map<String, Object> dashboard = new java.util.LinkedHashMap<>();
        dashboard.put("product", isvOnboardingService.getProduct(productCode));
        dashboard.put("eventStats", usageEventService.getEventStats(productCode));
        dashboard.put("recentMeteringRecords", meteringEngineService.getRecentMeteringRecords(productCode, 10));
        return ResponseEntity.ok(dashboard);
    }

    // ─── Manual trigger ───────────────────────────────────────────────────────

    /**
     * POST /api/isv/metering/trigger
     * Manually trigger the metering engine (admin only — for testing/recovery).
     * In production the scheduler runs this automatically every hour.
     */
    @PostMapping("/metering/trigger")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    public ResponseEntity<Map<String, Object>> triggerMetering() {
        log.info("Manual metering trigger by admin");
        Map<String, Object> result = meteringEngineService.processPendingEvents();
        return ResponseEntity.ok(result);
    }
}
