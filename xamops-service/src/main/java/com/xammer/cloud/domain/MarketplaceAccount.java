package com.xammer.cloud.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "marketplace_accounts")
@Data
@NoArgsConstructor
public class MarketplaceAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payer_account_id", nullable = false)
    @JsonIgnore
    private MarketplacePayerAccount payerAccount;

    /** AWS Account ID of this member account */
    @Column(nullable = false, unique = true)
    private String awsAccountId;

    @Column(nullable = false)
    private String accountName;

    /** Contact email from AWS Organizations */
    @Column
    private String contactEmail;

    /**
     * MARKETPLACE_XAMOPS — account is also connected to XamOps
     * MARKETPLACE_ONLY  — account is only on marketplace, not on XamOps
     */
    @Column(nullable = false)
    private String category;

    /** FK to Client if this account is also on XamOps, null otherwise */
    @Column
    private Long xamopsClientId;

    @Column(nullable = false)
    private String status = "ACTIVE";

    /** Whether billing data has been extracted to S3 + DB */
    @Column(nullable = false)
    private Boolean billingExtracted = false;

    @Column(nullable = false)
    private LocalDateTime discoveredAt = LocalDateTime.now();
}
