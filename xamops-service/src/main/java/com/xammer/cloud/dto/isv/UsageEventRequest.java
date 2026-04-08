package com.xammer.cloud.dto.isv;

import lombok.Data;

import java.time.Instant;

/**
 * Single usage event submitted by an ISV's product.
 * Authenticated via X-Api-Key header in the HTTP request.
 */
@Data
public class UsageEventRequest {

    /** AWS Marketplace customer identifier (from ResolveCustomer) */
    private String customerId;

    /** Dimension key (must match a registered dimension for this product) */
    private String dimension;

    /**
     * Usage quantity (must be >= 0).
     * AWS Marketplace accepts integer quantities only.
     */
    private Long quantity;

    /**
     * Optional: the timestamp when the usage occurred.
     * Defaults to the current time if not provided.
     * AWS requires timestamps within the past 6 hours.
     */
    private Instant timestamp;
}
