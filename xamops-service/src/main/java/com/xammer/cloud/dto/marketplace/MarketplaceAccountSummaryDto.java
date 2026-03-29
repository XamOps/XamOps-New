package com.xammer.cloud.dto.marketplace;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class MarketplaceAccountSummaryDto {

    private Long id;
    private String awsAccountId;
    private String accountName;
    private String contactEmail;

    /** MARKETPLACE_XAMOPS or MARKETPLACE_ONLY */
    private String category;

    /** Whether billing data has been extracted to S3 + DB */
    private Boolean billingExtracted;

    private String status;
    private LocalDateTime discoveredAt;
}
