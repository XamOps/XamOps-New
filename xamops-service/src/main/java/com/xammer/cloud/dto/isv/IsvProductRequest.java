package com.xammer.cloud.dto.isv;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Request body for registering or updating an ISV product.
 */
@Data
public class IsvProductRequest {

    /** AWS Marketplace product code — immutable after creation */
    private String productCode;

    private String productName;
    private String description;
    private String ownerEmail;

    /**
     * IAM Role ARN in the ISV's AWS account.
     * Must have: aws-marketplace:BatchMeterUsage, aws-marketplace:ResolveCustomer,
     *            aws-marketplace:GetEntitlements
     */
    private String roleArn;

    /** Optional STS ExternalId for role assumption */
    private String externalId;

    /** Metering dimensions as registered on AWS Marketplace */
    private List<DimensionRequest> dimensions = new ArrayList<>();

    @Data
    public static class DimensionRequest {
        /** Must match exactly what's registered on AWS Marketplace */
        private String dimensionKey;
        private String description;
        private String unit;
    }
}
