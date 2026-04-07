package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "marketplace_payer_accounts")
@Data
@NoArgsConstructor
public class MarketplacePayerAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String accountName;

    /** The AWS Account ID of the marketplace/management (payer) account */
    @Column(nullable = false, unique = true)
    private String payerAwsAccountId;

    /** IAM Role ARN in the payer account that XamOps assumes */
    @Column(nullable = false)
    private String roleArn;

    /** ExternalId used in the trust policy — pre-agreed value */
    @Column(nullable = false)
    private String externalId = "xamops-marketplace-sync";

    @Column(nullable = false)
    private String status = "CONNECTED";

    @Column(nullable = false)
    private LocalDateTime connectedAt = LocalDateTime.now();

    @Column
    private LocalDateTime lastSyncedAt;

    /**
     * Markup percentage applied to all extracted billing rows at extraction time.
     * E.g. 5.3 means every row cost is multiplied by 1.053.
     * Default: 0.0 (no markup).
     */
    @Column(nullable = false)
    private double markupPercent = 0.0;
}
