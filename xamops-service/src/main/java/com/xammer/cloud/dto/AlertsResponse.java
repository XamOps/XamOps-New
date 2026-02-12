package com.xammer.cloud.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Enhanced response for alerts endpoint that includes error details and metadata.
 * Supports partial success scenarios where some services succeed and others fail.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AlertsResponse {
    private List<AlertDto> alerts = new ArrayList<>();
    private List<AlertError> errors = new ArrayList<>();
    private AlertsMetadata metadata;

    /**
     * Represents an error from a specific alert service.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AlertError {
        private String service;      // "VPC_QUOTA", "NETWORK_QUOTA", "COST_ANOMALY", "SECURITY"
        private String errorType;    // "TIMEOUT", "API_ERROR", "PERMISSION_DENIED", "RATE_LIMIT", "SERVICE_NOT_ENABLED", "UNKNOWN"
        private String message;
        private boolean partial;     // If true, some data was retrieved despite error
        private LocalDateTime timestamp;

        public AlertError(String service, String errorType, String message) {
            this.service = service;
            this.errorType = errorType;
            this.message = message;
            this.partial = false;
            this.timestamp = LocalDateTime.now();
        }
    }

    /**
     * Metadata about the alert fetch operation.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AlertsMetadata {
        private int totalAlerts;
        private int vpcQuotaCount;
        private int networkQuotaCount;
        private int costAnomalyCount;
        private int securityAlertCount;
        private long fetchDurationMs;
        private LocalDateTime timestamp;
        private boolean fromCache;

        public AlertsMetadata(int totalAlerts, long fetchDurationMs, boolean fromCache) {
            this.totalAlerts = totalAlerts;
            this.fetchDurationMs = fetchDurationMs;
            this.fromCache = fromCache;
            this.timestamp = LocalDateTime.now();
            this.vpcQuotaCount = 0;
            this.networkQuotaCount = 0;
            this.costAnomalyCount = 0;
            this.securityAlertCount = 0;
        }
    }
}
