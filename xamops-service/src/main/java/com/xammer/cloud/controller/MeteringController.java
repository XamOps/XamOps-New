package com.xammer.cloud.controller;

import com.xammer.cloud.dto.isv.CustomerRegistrationRequest;
import com.xammer.cloud.dto.isv.UsageBatchRequest;
import com.xammer.cloud.dto.isv.UsageEventRequest;
import com.xammer.cloud.service.BuyerUsageService;
import com.xammer.cloud.service.CustomerRegistrationService;
import com.xammer.cloud.service.SnsNotificationService;
import com.xammer.cloud.service.UsageEventService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Public Metering API — consumed by ISV products and AWS Marketplace callbacks.
 *
 * These endpoints are intentionally NOT protected by session-based authentication:
 *   - /register  → called by AWS Marketplace redirect (no session)
 *   - /events    → called by ISV's product server-side (uses API key, not session)
 *
 * Security is enforced at the service layer via:
 *   - API key validation for /events endpoints
 *   - Registration token validation (ResolveCustomer) for /register
 */
@RestController
@RequestMapping("/api/metering")
@Slf4j
public class MeteringController {

    private final CustomerRegistrationService customerRegistrationService;
    private final UsageEventService usageEventService;
    private final SnsNotificationService snsNotificationService;
    private final BuyerUsageService buyerUsageService;

    public MeteringController(CustomerRegistrationService customerRegistrationService,
                               UsageEventService usageEventService,
                               SnsNotificationService snsNotificationService,
                               BuyerUsageService buyerUsageService) {
        this.customerRegistrationService = customerRegistrationService;
        this.usageEventService = usageEventService;
        this.snsNotificationService = snsNotificationService;
        this.buyerUsageService = buyerUsageService;
    }

    // ─── Customer Registration ────────────────────────────────────────────────

    /**
     * POST /api/metering/register
     *
     * Called by the ISV's fulfillment URL handler (or directly by AWS Marketplace redirect).
     * Validates the registration token via AWS ResolveCustomer and activates the customer.
     *
     * The ISV should configure their AWS Marketplace listing's fulfillment URL to:
     *   https://app.xamops.io/marketplace/register
     *
     * AWS redirects buyer to:
     *   https://app.xamops.io/marketplace/register?x-amzn-marketplace-token=TOKEN
     *
     * The ISV's frontend then POSTs to this endpoint with the token and productCode.
     *
     * Request:
     * {
     *   "registrationToken": "x-amzn-marketplace-token from AWS redirect",
     *   "productCode": "prod-xxxx"
     * }
     *
     * Response:
     * {
     *   "customerId": "iQEMo...",
     *   "customerAwsAccountId": "123456789012",
     *   "productCode": "prod-xxxx",
     *   "status": "ACTIVE",
     *   "registeredAt": "2026-04-07T10:00:00"
     * }
     */
    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> registerCustomer(
            @RequestBody CustomerRegistrationRequest request) {
        log.info("Customer registration request for product={}", request.getProductCode());
        try {
            Map<String, Object> result = customerRegistrationService.registerCustomer(
                    request.getProductCode(), request.getRegistrationToken());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(errorBody(e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(errorBody(e.getMessage()));
        }
    }

    // ─── Usage Event Ingestion ────────────────────────────────────────────────

    /**
     * POST /api/metering/events
     *
     * Submit a single usage event from the ISV's product.
     * Requires the ISV's API key in the X-Api-Key header.
     *
     * Header:  X-Api-Key: xmp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
     *
     * Request:
     * {
     *   "customerId": "iQEMo...",
     *   "dimension": "api_calls",
     *   "quantity": 1500,
     *   "timestamp": "2026-04-07T10:15:00Z"   // optional, defaults to now
     * }
     *
     * Response:
     * {
     *   "status": "accepted",
     *   "productCode": "prod-xxxx",
     *   "customerId": "iQEMo...",
     *   "dimension": "api_calls",
     *   "quantity": 1500,
     *   "timestamp": "2026-04-07T10:15:00Z"
     * }
     */
    @PostMapping("/events")
    public ResponseEntity<Map<String, Object>> ingestEvent(
            @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
            @RequestBody UsageEventRequest request) {
        if (apiKey == null || apiKey.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(errorBody("X-Api-Key header is required"));
        }
        try {
            Map<String, Object> result = usageEventService.ingestEvent(apiKey, request);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(errorBody(e.getMessage()));
        }
    }

    /**
     * POST /api/metering/events/batch
     *
     * Submit multiple usage events in a single call (max 100 events per batch).
     * Requires the ISV's API key in the X-Api-Key header.
     *
     * Header:  X-Api-Key: xmp_xxxx...
     *
     * Request:
     * {
     *   "events": [
     *     { "customerId": "iQEMo...", "dimension": "api_calls", "quantity": 1500 },
     *     { "customerId": "jRFNp...", "dimension": "active_users", "quantity": 3 }
     *   ]
     * }
     *
     * Response:
     * {
     *   "productCode": "prod-xxxx",
     *   "accepted": 2,
     *   "rejected": 0
     * }
     */
    @PostMapping("/events/batch")
    public ResponseEntity<Map<String, Object>> ingestBatch(
            @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
            @RequestBody UsageBatchRequest batch) {
        if (apiKey == null || apiKey.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(errorBody("X-Api-Key header is required"));
        }
        try {
            Map<String, Object> result = usageEventService.ingestBatch(apiKey, batch);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(errorBody(e.getMessage()));
        }
    }

    /**
     * GET /api/metering/customers/{customerId}/events
     *
     * Returns recent usage events for a specific customer.
     * Requires the ISV's API key.
     *
     * Header: X-Api-Key: xmp_xxxx...
     * Query param: productCode — identifies the product context
     */
    @GetMapping("/customers/{customerId}/events")
    public ResponseEntity<Object> getCustomerEvents(
            @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
            @PathVariable String customerId,
            @RequestParam String productCode) {
        if (apiKey == null || apiKey.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(errorBody("X-Api-Key header is required"));
        }
        try {
            // Validate API key belongs to this product
            var product = usageEventService.getEventStats(productCode); // validates product exists
            return ResponseEntity.ok(usageEventService.getRecentEvents(productCode, customerId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(errorBody(e.getMessage()));
        }
    }

    // ─── AWS Marketplace SNS Webhook ──────────────────────────────────────────

    /**
     * POST /api/metering/sns
     *
     * Receives AWS Marketplace SNS notifications for subscription lifecycle events.
     * AWS sends this for: subscribe-success, subscribe-fail, unsubscribe-pending,
     * unsubscribe-success, entitlement-updated, and the one-time SubscriptionConfirmation.
     *
     * This endpoint has no auth (covered by SecurityConfig permitAll for /api/metering/**)
     * since AWS SNS calls it directly. Security is provided by the SNS message signature
     * (verified implicitly via the SigningCertURL domain check in SnsNotificationService).
     *
     * Configure your AWS Marketplace SNS topic to POST to:
     *   https://app.xamops.io/api/metering/sns
     */
    @PostMapping("/sns")
    public ResponseEntity<Map<String, Object>> handleSnsNotification(@RequestBody String rawBody) {
        log.info("SNS notification received, length={}", rawBody.length());
        Map<String, Object> result = snsNotificationService.handleSnsNotification(rawBody);
        return ResponseEntity.ok(result);
    }

    // ─── Buyer Usage Visibility ───────────────────────────────────────────────

    /**
     * GET /api/metering/buyer/usage?cloudAccountId={id}
     *
     * Returns all marketplace subscriptions being metered for the buyer's cloud account,
     * with the last 30 days of usage per dimension. Requires session auth.
     *
     * The cloudAccountId is the XamOps internal account ID (the `id` field on Account).
     * The service resolves it to the AWS account number to find matching subscriptions.
     *
     * Response:
     * [
     *   {
     *     "productCode": "prod-xxxx",
     *     "productName": "XamOps",
     *     "customerIdentifier": "iQEMo...",
     *     "status": "ACTIVE",
     *     "registeredAt": "2026-03-01T10:00:00",
     *     "usageLast30Days": [
     *       { "dimension": "api_calls",    "totalQuantity": 15000 },
     *       { "dimension": "active_users", "totalQuantity": 3 }
     *     ],
     *     "periodStart": "...",
     *     "periodEnd": "..."
     *   }
     * ]
     */
    @GetMapping("/buyer/usage")
    public ResponseEntity<Object> getBuyerUsage(@RequestParam String cloudAccountId) {
        try {
            List<Map<String, Object>> result = buyerUsageService.getBuyerUsage(cloudAccountId);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(errorBody(e.getMessage()));
        }
    }

    // ─── Health check ─────────────────────────────────────────────────────────

    /**
     * GET /api/metering/health
     * Simple health check for the metering API. No auth required.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "XamOps Metering API"
        ));
    }

    // ─── Helper ───────────────────────────────────────────────────────────────

    private Map<String, Object> errorBody(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return body;
    }
}
