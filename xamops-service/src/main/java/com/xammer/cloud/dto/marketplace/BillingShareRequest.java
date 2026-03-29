package com.xammer.cloud.dto.marketplace;

import lombok.Data;

@Data
public class BillingShareRequest {

    /** AWS Account ID of the customer whose billing data to share */
    private String awsAccountId;

    /** Email address to send the download link to */
    private String recipientEmail;

    /** Billing month to share, e.g. "2025-02". Required when per-month CSVs are used. */
    private String yearMonth;
}
