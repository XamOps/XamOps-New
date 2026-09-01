package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * Represents one record submitted to AWS Marketplace via BatchMeterUsage.
 * Each record is the aggregated usage for one (customer, dimension, hour) combination.
 * AWS returns a meteringRecordId as proof the record was accepted.
 *
 * Status lifecycle:
 *   SUBMITTED → ACCEPTED (AWS confirmed) or FAILED (AWS rejected)
 */
@Entity
@Table(name = "metering_record",
       indexes = {
           @Index(name = "idx_metering_record_product",    columnList = "productCode"),
           @Index(name = "idx_metering_record_customer",   columnList = "customerIdentifier"),
           @Index(name = "idx_metering_record_submitted",  columnList = "submittedAt")
       })
@Data
@NoArgsConstructor
public class MeteringRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String productCode;

    /** AWS Marketplace customer identifier */
    @Column(nullable = false, length = 255)
    private String customerIdentifier;

    /** Dimension key (e.g., "api_calls") */
    @Column(nullable = false, length = 100)
    private String dimension;

    /** Total aggregated quantity submitted for this hour */
    @Column(nullable = false)
    private Long quantity;

    /** The usage timestamp reported to AWS (beginning of the billing hour) */
    @Column(nullable = false)
    private Instant usageTimestamp;

    /** AWS Marketplace metering record ID returned by BatchMeterUsage (proof of billing) */
    @Column(length = 255)
    private String awsMeteringRecordId;

    /**
     * SUBMITTED   — sent to AWS, awaiting confirmation
     * SUCCESS     — AWS accepted and confirmed (status = "Success")
     * FAILED      — AWS rejected (CustomerNotSubscribed, DuplicateRecord, or API error)
     */
    @Column(nullable = false, length = 20)
    private String status = "SUBMITTED";

    @Column(nullable = false)
    private LocalDateTime submittedAt = LocalDateTime.now();

    /** Error detail when status = FAILED */
    @Column(length = 1000)
    private String errorMessage;
}
