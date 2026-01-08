package com.xammer.cloud.dto.cloudidr;

import lombok.Data;
import javax.validation.constraints.NotBlank;

@Data
public class CloudIdrLinkAccountRequest {
    @NotBlank(message = "AWS Account ID is required")
    private String awsAccountId;

    @NotBlank(message = "Role ARN is required")
    private String roleArn; // arn:aws:iam::605134457560:role/CloudidrRole

    private String accountName; // Optional friendly name
}
