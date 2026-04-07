package com.xammer.cloud.dto.marketplace;

import lombok.Data;

@Data
public class ConnectMarketplaceRequest {

    /** Human-readable name for this marketplace account */
    private String accountName;

    /** IAM Role ARN created in the payer account for XamOps to assume */
    private String roleArn;

    /**
     * ExternalId used in the IAM trust policy.
     * Defaults to "xamops-marketplace-sync" if not provided.
     */
    private String externalId = "xamops-marketplace-sync";
}
