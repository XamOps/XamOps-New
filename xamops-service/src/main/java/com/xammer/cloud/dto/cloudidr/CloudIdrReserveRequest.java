package com.xammer.cloud.dto.cloudidr;

import lombok.Data;

@Data
public class CloudIdrReserveRequest {
    private String accountId;
    private String policyName;
    private String instanceType;
    private String platform;
    private Integer quantity;
    private String region; // Format: "us-west-2/usw2-az1"
    private String startDateTime; // From frontend datetime-local: "2026-01-08T12:00"
    private String endDateTime; // From frontend datetime-local: "2026-01-09T12:00"
    private String timezone; // e.g., "America/Los_Angeles"
    private String autoRenewal; // "Yes" or "No"
}
