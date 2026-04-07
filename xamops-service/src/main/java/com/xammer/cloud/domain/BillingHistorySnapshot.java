package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "billing_history_snapshot")
@Data
@NoArgsConstructor
public class BillingHistorySnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String awsAccountId;

    @Column
    private String accountName;

    @Column(nullable = false)
    private Integer year;

    @Column(nullable = false)
    private Integer month;

    @Column(nullable = false)
    private String serviceName;

    @Column
    private String region;

    @Column(nullable = false, precision = 18, scale = 6)
    private BigDecimal costUsd = BigDecimal.ZERO;

    @Column
    private Double usageQuantity;

    /** Always PRE_BILLING_CONDUCTOR_V1 for this historical batch */
    @Column(nullable = false)
    private String snapshotVersion = "PRE_BILLING_CONDUCTOR_V1";

    @Column(nullable = false)
    private LocalDateTime pulledAt = LocalDateTime.now();

    public BillingHistorySnapshot(String awsAccountId, String accountName,
                                   int year, int month,
                                   String serviceName, BigDecimal costUsd, Double usageQuantity) {
        this.awsAccountId = awsAccountId;
        this.accountName = accountName;
        this.year = year;
        this.month = month;
        this.serviceName = serviceName;
        this.costUsd = costUsd;
        this.usageQuantity = usageQuantity;
    }
}
