package com.xammer.cloud.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Audit log for every AWS Marketplace SNS subscription event received by XamOps.
 *
 * AWS sends SNS notifications to POST /api/metering/sns for events:
 *   subscribe-success      — buyer confirmed subscription
 *   subscribe-fail         — subscription attempt failed
 *   unsubscribe-pending    — buyer initiated cancellation (grace period)
 *   unsubscribe-success    — cancellation complete
 *   entitlement-updated    — entitlement changed (contract products)
 *   subscription-confirmation — SNS topic subscription confirmation (one-time)
 */
@Entity
@Table(name = "marketplace_subscription_event",
       indexes = {
           @Index(name = "idx_mse_product",           columnList = "productCode"),
           @Index(name = "idx_mse_customer",          columnList = "customerIdentifier"),
           @Index(name = "idx_mse_product_received",  columnList = "productCode, receivedAt")
       })
public class MarketplaceSubscriptionEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** ISV product code from the marketplace event payload. "system" for SNS confirmations. */
    @Column(nullable = false)
    private String productCode;

    /** AWS Marketplace customer identifier. Null for non-customer events (confirmations). */
    private String customerIdentifier;

    /**
     * Event type — one of:
     *   subscribe-success, subscribe-fail,
     *   unsubscribe-pending, unsubscribe-success,
     *   entitlement-updated, subscription-confirmation
     */
    @Column(nullable = false)
    private String eventType;

    /** PROCESSED | FAILED | IGNORED */
    @Column(nullable = false)
    private String status;

    /** Full raw SNS envelope payload for debugging. */
    @Column(columnDefinition = "TEXT")
    private String rawPayload;

    /** Error message when status=FAILED. */
    @Column(length = 1000)
    private String errorMessage;

    @Column(nullable = false)
    private Instant receivedAt;

    private Instant processedAt;

    // ─── Getters / Setters ────────────────────────────────────────────────────

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getProductCode() { return productCode; }
    public void setProductCode(String productCode) { this.productCode = productCode; }

    public String getCustomerIdentifier() { return customerIdentifier; }
    public void setCustomerIdentifier(String customerIdentifier) { this.customerIdentifier = customerIdentifier; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getRawPayload() { return rawPayload; }
    public void setRawPayload(String rawPayload) { this.rawPayload = rawPayload; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant receivedAt) { this.receivedAt = receivedAt; }

    public Instant getProcessedAt() { return processedAt; }
    public void setProcessedAt(Instant processedAt) { this.processedAt = processedAt; }
}
