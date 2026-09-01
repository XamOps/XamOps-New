package com.xammer.cloud.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.IsvMarketplaceCustomer;
import com.xammer.cloud.domain.MarketplaceSubscriptionEvent;
import com.xammer.cloud.repository.IsvMarketplaceCustomerRepository;
import com.xammer.cloud.repository.MarketplaceSubscriptionEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Processes AWS Marketplace SNS notifications.
 *
 * AWS sends an SNS HTTP POST to POST /api/metering/sns for every marketplace event.
 * The outer envelope is an SNS JSON wrapper. The inner "Message" field contains
 * the marketplace event JSON.
 *
 * Handled event types:
 *   subscribe-success         → activates the customer (or creates + activates)
 *   unsubscribe-success       → cancels the customer
 *   unsubscribe-pending       → logs as IGNORED (grace period — don't cancel yet)
 *   entitlement-updated       → refreshes lastEntitlementCheck timestamp on the customer
 *   subscribe-fail            → logs as IGNORED
 *   subscription-confirmation → confirms the SNS topic subscription (one-time)
 *
 * Every event is persisted to marketplace_subscription_event regardless of outcome.
 */
@Service
@Slf4j
public class SnsNotificationService {

    private final MarketplaceSubscriptionEventRepository eventRepository;
    private final IsvMarketplaceCustomerRepository customerRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final SnsSignatureVerifier snsSignatureVerifier;

    public SnsNotificationService(
            MarketplaceSubscriptionEventRepository eventRepository,
            IsvMarketplaceCustomerRepository customerRepository,
            ObjectMapper objectMapper,
            SnsSignatureVerifier snsSignatureVerifier) {
        this.eventRepository = eventRepository;
        this.customerRepository = customerRepository;
        this.objectMapper = objectMapper;
        this.snsSignatureVerifier = snsSignatureVerifier;
        this.httpClient = HttpClient.newHttpClient();
    }

    // ─── Main entry point ─────────────────────────────────────────────────────

    /**
     * Process a raw SNS notification body.
     *
     * @param rawBody the full HTTP request body from AWS SNS
     * @return processing result summary
     */
    @Transactional
    public Map<String, Object> handleSnsNotification(String rawBody) {
        Instant receivedAt = Instant.now();
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            JsonNode envelope = objectMapper.readTree(rawBody);
            String snsType = envelope.path("Type").asText("");

            // ── Origin check, before anything is trusted or acted on ──────────
            //
            // This endpoint is public and drives billing state, and nothing here proved the
            // request came from AWS. A forged POST could activate a paid entitlement or
            // cancel a real customer's subscription. Verification therefore runs first, for
            // every message type — including the handshake, whose only previous check was a
            // string comparison on a caller-supplied URL.
            if (!snsSignatureVerifier.isValid(envelope, rawBody)) {
                saveAuditEvent("system", null, snsType, "REJECTED",
                        rawBody, "SNS signature verification failed", receivedAt);
                result.put("status", "rejected");
                result.put("reason", "signature verification failed");
                return result;
            }

            // ── SNS Subscription Confirmation (one-time handshake) ────────────
            if ("SubscriptionConfirmation".equals(snsType)) {
                return handleSubscriptionConfirmation(envelope, rawBody, receivedAt);
            }

            // ── Normal notification ───────────────────────────────────────────
            if (!"Notification".equals(snsType)) {
                log.warn("Unknown SNS Type: {}", snsType);
                result.put("status", "ignored");
                result.put("reason", "unknown SNS type: " + snsType);
                return result;
            }

            String messageStr = envelope.path("Message").asText("");
            JsonNode message = objectMapper.readTree(messageStr);

            String action = message.path("action").asText("");
            String customerIdentifier = message.path("customer-identifier").asText(null);
            String productCode = message.path("product-code").asText(null);

            log.info("SNS marketplace event: action={} product={} customer={}",
                    action, productCode, customerIdentifier);

            return dispatchAction(action, productCode, customerIdentifier, rawBody, receivedAt);

        } catch (Exception e) {
            log.error("Failed to process SNS notification: {}", e.getMessage(), e);
            saveAuditEvent("unknown", null, "unknown", "FAILED", rawBody, e.getMessage(), receivedAt);
            result.put("status", "error");
            result.put("error", e.getMessage());
            return result;
        }
    }

    // ─── Action dispatcher ────────────────────────────────────────────────────

    private Map<String, Object> dispatchAction(
            String action, String productCode, String customerIdentifier,
            String rawBody, Instant receivedAt) {

        Map<String, Object> result = new LinkedHashMap<>();

        switch (action) {
            case "subscribe-success" -> {
                activateCustomer(productCode, customerIdentifier);
                saveAuditEvent(productCode, customerIdentifier, action, "PROCESSED", rawBody, null, receivedAt);
                result.put("status", "processed");
                result.put("action", action);
                result.put("customerIdentifier", customerIdentifier);
                result.put("productCode", productCode);
            }
            case "unsubscribe-success" -> {
                deactivateCustomer(productCode, customerIdentifier);
                saveAuditEvent(productCode, customerIdentifier, action, "PROCESSED", rawBody, null, receivedAt);
                result.put("status", "processed");
                result.put("action", action);
            }
            case "entitlement-updated" -> {
                // Refresh the entitlement check timestamp — confirms the customer is still entitled
                refreshEntitlementCheck(productCode, customerIdentifier);
                saveAuditEvent(productCode, customerIdentifier, action, "PROCESSED", rawBody, null, receivedAt);
                result.put("status", "processed");
                result.put("action", action);
            }
            case "unsubscribe-pending", "subscribe-fail" -> {
                // Log but do not change customer status yet
                saveAuditEvent(productCode, customerIdentifier, action, "IGNORED", rawBody, null, receivedAt);
                result.put("status", "ignored");
                result.put("action", action);
            }
            default -> {
                log.warn("Unrecognised marketplace action: {}", action);
                saveAuditEvent(productCode, customerIdentifier, action, "IGNORED", rawBody,
                        "unrecognised action", receivedAt);
                result.put("status", "ignored");
                result.put("action", action);
            }
        }
        return result;
    }

    // ─── Customer lifecycle ───────────────────────────────────────────────────

    private void activateCustomer(String productCode, String customerIdentifier) {
        IsvMarketplaceCustomer customer = customerRepository
                .findByProductCodeAndCustomerIdentifier(productCode, customerIdentifier)
                .orElseGet(IsvMarketplaceCustomer::new);

        boolean isNew = customer.getId() == null;
        customer.setProductCode(productCode);
        customer.setCustomerIdentifier(customerIdentifier);
        customer.setStatus("ACTIVE");
        if (isNew || customer.getRegisteredAt() == null) {
            customer.setRegisteredAt(LocalDateTime.now());
        }
        customer.setCancelledAt(null);
        customerRepository.save(customer);
        log.info("Customer {} activated for product={} (new={})", customerIdentifier, productCode, isNew);
    }

    private void deactivateCustomer(String productCode, String customerIdentifier) {
        customerRepository
                .findByProductCodeAndCustomerIdentifier(productCode, customerIdentifier)
                .ifPresentOrElse(customer -> {
                    customer.setStatus("CANCELLED");
                    customer.setCancelledAt(LocalDateTime.now());
                    customerRepository.save(customer);
                    log.info("Customer {} cancelled for product={}", customerIdentifier, productCode);
                }, () -> log.warn("Cannot cancel unknown customer={} product={}", customerIdentifier, productCode));
    }

    private void refreshEntitlementCheck(String productCode, String customerIdentifier) {
        customerRepository
                .findByProductCodeAndCustomerIdentifier(productCode, customerIdentifier)
                .ifPresent(customer -> {
                    customer.setLastEntitlementCheck(LocalDateTime.now());
                    customerRepository.save(customer);
                    log.info("Entitlement refreshed for customer={} product={}", customerIdentifier, productCode);
                });
    }

    /**
     * Called by MeteringEngineService when AWS returns CustomerNotSubscribed for a customer.
     * Marks the customer as EXPIRED so they stop being metered.
     */
    public void expireCustomer(String productCode, String customerIdentifier, String reason) {
        customerRepository
                .findByProductCodeAndCustomerIdentifier(productCode, customerIdentifier)
                .ifPresentOrElse(customer -> {
                    if ("ACTIVE".equals(customer.getStatus())) {
                        customer.setStatus("EXPIRED");
                        customer.setCancelledAt(LocalDateTime.now());
                        customerRepository.save(customer);
                        log.warn("Customer {} EXPIRED for product={} reason={}", customerIdentifier, productCode, reason);
                    }
                }, () -> log.warn("Cannot expire unknown customer={} product={}", customerIdentifier, productCode));
    }

    // ─── SNS Subscription Confirmation ───────────────────────────────────────

    private Map<String, Object> handleSubscriptionConfirmation(
            JsonNode envelope, String rawBody, Instant receivedAt) {

        Map<String, Object> result = new LinkedHashMap<>();
        String subscribeUrl = envelope.path("SubscribeURL").asText(null);

        if (subscribeUrl == null || subscribeUrl.isBlank()) {
            saveAuditEvent("system", null, "subscription-confirmation", "FAILED",
                    rawBody, "missing SubscribeURL", receivedAt);
            result.put("status", "error");
            result.put("error", "missing SubscribeURL in SNS confirmation");
            return result;
        }

        // Security check: must come from amazonaws.com
        try {
            URI uri = URI.create(subscribeUrl);
            String host = uri.getHost();
            if (host == null || !host.endsWith(".amazonaws.com")) {
                String msg = "Rejected SubscribeURL with non-AWS host: " + host;
                log.warn(msg);
                saveAuditEvent("system", null, "subscription-confirmation", "FAILED",
                        rawBody, msg, receivedAt);
                result.put("status", "error");
                result.put("error", msg);
                return result;
            }
        } catch (IllegalArgumentException e) {
            saveAuditEvent("system", null, "subscription-confirmation", "FAILED",
                    rawBody, "invalid SubscribeURL: " + e.getMessage(), receivedAt);
            result.put("status", "error");
            result.put("error", "invalid SubscribeURL");
            return result;
        }

        // Confirm the SNS subscription with a GET request
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(subscribeUrl))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            log.info("SNS subscription confirmed, HTTP status={}", response.statusCode());
            saveAuditEvent("system", null, "subscription-confirmation", "PROCESSED",
                    rawBody, null, receivedAt);
            result.put("status", "confirmed");
            result.put("httpStatus", response.statusCode());
        } catch (Exception e) {
            log.error("Failed to confirm SNS subscription: {}", e.getMessage(), e);
            saveAuditEvent("system", null, "subscription-confirmation", "FAILED",
                    rawBody, e.getMessage(), receivedAt);
            result.put("status", "error");
            result.put("error", "confirmation GET failed: " + e.getMessage());
        }
        return result;
    }

    // ─── Audit log ────────────────────────────────────────────────────────────

    private void saveAuditEvent(String productCode, String customerIdentifier,
                                 String eventType, String status,
                                 String rawPayload, String errorMessage,
                                 Instant receivedAt) {
        MarketplaceSubscriptionEvent event = new MarketplaceSubscriptionEvent();
        event.setProductCode(productCode != null ? productCode : "unknown");
        event.setCustomerIdentifier(customerIdentifier);
        event.setEventType(eventType);
        event.setStatus(status);
        event.setRawPayload(rawPayload);
        event.setErrorMessage(errorMessage);
        event.setReceivedAt(receivedAt);
        event.setProcessedAt(Instant.now());
        eventRepository.save(event);
    }

    // ─── Query helpers ────────────────────────────────────────────────────────

    /**
     * Returns recent SNS events for a product as a list of maps (for the API response).
     *
     * @param productCode product to query
     * @param limit       max number of events to return (capped at 200)
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getRecentEvents(String productCode, int limit) {
        int safeLimit = Math.min(limit, 200);
        return eventRepository
                .findByProductCodeOrderByReceivedAtDesc(
                        productCode,
                        org.springframework.data.domain.PageRequest.of(0, safeLimit))
                .stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    private Map<String, Object> toDto(MarketplaceSubscriptionEvent e) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", e.getId());
        dto.put("productCode", e.getProductCode());
        dto.put("customerIdentifier", e.getCustomerIdentifier());
        dto.put("eventType", e.getEventType());
        dto.put("status", e.getStatus());
        dto.put("errorMessage", e.getErrorMessage());
        dto.put("receivedAt", e.getReceivedAt() != null ? e.getReceivedAt().toString() : null);
        dto.put("processedAt", e.getProcessedAt() != null ? e.getProcessedAt().toString() : null);
        // rawPayload intentionally excluded from API response (can be large and sensitive)
        return dto;
    }
}
