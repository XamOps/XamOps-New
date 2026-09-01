package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "marketplace_meter_billing")
@Data
@NoArgsConstructor
public class MarketplaceMeterBilling {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String cloudAccountId;

    @Column(nullable = false)
    private String productCode;

    @Column(nullable = false)
    private String yearMonth;

    @Column
    private String usageType;

    @Column
    private Double usageQuantity;

    @Column(nullable = false, precision = 18, scale = 6)
    private BigDecimal costUsd = BigDecimal.ZERO;

    @Column(nullable = false)
    private LocalDateTime syncedAt = LocalDateTime.now();
}
