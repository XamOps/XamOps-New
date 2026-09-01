package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * A raw usage event pushed by an ISV's product to XamOps via the metering API.
 * Events are batched and submitted to AWS Marketplace via BatchMeterUsage by the
 * hourly MeteringScheduler. Each event has a status lifecycle:
 *   PENDING → SUBMITTED (success) or FAILED (AWS rejected)
 */
@Entity
@Table(name = "usage_event",
       indexes = {
           @Index(name = "idx_usage_event_product_status", columnList = "productCode,status"),
           @Index(name = "idx_usage_event_customer",       columnList = "customerIdentifier"),
           @Index(name = "idx_usage_event_created",        columnList = "createdAt")
       })
@Data
@NoArgsConstructor
public class UsageEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String productCode;

    /** AWS Marketplace customer identifier */
    @Column(nullable = false, length = 255)
    private String customerIdentifier;

    /** Dimension key (must match a registered IsvProductDimension) */
    @Column(nullable = false, length = 100)
    private String dimension;

    /** Usage quantity (non-negative integer) */
    @Column(nullable = false)
    private Long quantity;

    /**
     * The timestamp of the usage event as provided by the ISV.
     * Defaults to ingestion time. Used to round to the nearest hour
     * when submitting to AWS BatchMeterUsage.
     */
    @Column(nullable = false)
    private Instant eventTimestamp;

    /**
     * PENDING   — waiting for next scheduler run
     * SUBMITTED — successfully included in a BatchMeterUsage call
     * FAILED    — AWS rejected the record (see MeteringRecord for error detail)
     */
    @Column(nullable = false, length = 20)
    private String status = "PENDING";

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column
    private LocalDateTime processedAt;

    /** FK to the MeteringRecord that submitted this event (null until processed) */
    @Column
    private Long meteringRecordId;
}
