package com.xammer.cloud.dto;

import lombok.Data;
import java.util.List;

@Data
public class CloudsitterAssignmentRequest {
    private Long policyId;
    private List<String> instanceIds; // List of instance IDs to assign
    private String accountId;
    private String region;
}