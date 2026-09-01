package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * A buyer/customer who has subscribed to an ISV's product on AWS Marketplace.
 * Created when a buyer completes the subscription flow and the ISV's registration URL
 * is called by AWS with the registration token. XamOps calls ResolveCustomer() to
 * validate the token and extract the customerIdentifier.
 */
@Entity
@Table(name = "isv_marketplace_customer",
       uniqueConstraints = @UniqueConstraint(columnNames = {"productCode", "customerIdentifier"}),
       indexes = {
           @Index(name = "idx_isv_customer_product", columnList = "productCode"),
           @Index(name = "idx_isv_customer_identifier", columnList = "customerIdentifier")
       })
@Data
@NoArgsConstructor
public class IsvMarketplaceCustomer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** AWS Marketplace product code */
    @Column(nullable = false, length = 100)
    private String productCode;

    /** Unique customer identifier returned by AWS ResolveCustomer API */
    @Column(nullable = false, length = 255)
    private String customerIdentifier;

    /** The buyer's AWS Account ID (returned by ResolveCustomer) */
    @Column(length = 20)
    private String customerAwsAccountId;

    /**
     * Customer status:
     *  ACTIVE    — subscription active, metering allowed
     *  CANCELLED — buyer unsubscribed
     *  EXPIRED   — entitlement expired
     */
    @Column(nullable = false, length = 20)
    private String status = "ACTIVE";

    @Column(nullable = false)
    private LocalDateTime registeredAt = LocalDateTime.now();

    @Column
    private LocalDateTime cancelledAt;

    /** Timestamp of last GetEntitlements check against AWS */
    @Column
    private LocalDateTime lastEntitlementCheck;
}
