package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Represents an AWS Marketplace SaaS product registered by an ISV (Independent Software Vendor)
 * for metering-as-a-service. The ISV registers their product code, IAM role, and dimensions here.
 * XamOps uses the role ARN to call BatchMeterUsage on their behalf.
 */
@Entity
@Table(name = "isv_product")
@Data
@NoArgsConstructor
public class IsvProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** AWS Marketplace product code (e.g. "prod-xxxx" or UUID) — must match exactly */
    @Column(nullable = false, unique = true, length = 100)
    private String productCode;

    @Column(nullable = false, length = 200)
    private String productName;

    @Column(length = 1000)
    private String description;

    /** Contact email of the ISV who owns this product */
    @Column(nullable = false, length = 255)
    private String ownerEmail;

    /**
     * IAM Role ARN in the ISV's AWS account with permissions:
     *   - aws-marketplace:BatchMeterUsage
     *   - aws-marketplace:ResolveCustomer
     *   - aws-marketplace:GetEntitlements
     */
    @Column(nullable = false, length = 512)
    private String roleArn;

    /** Optional STS ExternalId for role assumption security */
    @Column(length = 255)
    private String externalId;

    /**
     * Secret API key issued to the ISV for pushing usage events.
     * Format: "xmp_" + 32 random hex chars
     */
    @Column(nullable = false, unique = true, length = 100)
    private String apiKey;

    /** ACTIVE or INACTIVE */
    @Column(nullable = false, length = 20)
    private String status = "ACTIVE";

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column
    private LocalDateTime updatedAt;
}
