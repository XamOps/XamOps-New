package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "marketplace_product_subscription")
@Data
@NoArgsConstructor
public class MarketplaceProductSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String cloudAccountId;

    @Column(nullable = false)
    private String productCode;

    @Column
    private String productTitle;

    @Column
    private String vendorName;

    @Column(nullable = false)
    private String subscriptionStatus = "ACTIVE";

    @Column
    private LocalDate expiryDate;

    @Column(nullable = false)
    private LocalDateTime syncedAt = LocalDateTime.now();
}
