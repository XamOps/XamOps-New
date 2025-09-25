package com.xammer.cloud.dto.gcp;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * DTO for representing an IAM policy drift report.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GcpIamPolicyDriftDto {
    private String resourceName;
    private List<DriftDetail> driftDetails;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DriftDetail {
        private String member; // e.g., "user:test@example.com"
        private String role;   // e.g., "roles/owner"
        private String status; // "ADDED" or "REMOVED"
    }
}