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

    // Agreement details — populated from DescribeAgreement + GetAgreementTerms
    @Column
    private String agreementId;

    @Column
    private LocalDate startDate;

    /** PUBLIC, PRIVATE, or ENTERPRISE_CONTRACT */
    @Column
    private String offerType;

    /** FIXED_UPFRONT, RECURRING, FREE_TRIAL, PAY_AS_YOU_GO */
    @Column
    private String paymentSchedule;

    @Column
    private Boolean autoRenew;

    /** Human-readable payment term summary, e.g. "$500/month" or "$5,000 upfront for 12 months" */
    @Column(length = 500)
    private String paymentTermSummary;

    // Purchase Order fields — stored in XamOps only, not synced to AWS
    @Column
    private String poNumber;

    @Column(length = 1000)
    private String poNotes;
}
