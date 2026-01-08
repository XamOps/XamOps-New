package com.xammer.cloud.dto.cloudidr;

import lombok.Data;

@Data
public class CloudIdrLinkAccountResponse {
    private String cspAccountId; // CloudIDR's internal ID
    private String awsAccountId;
    private String roleArn;
    private String status;
    private String message;
    private String linkedAt;
}
