package com.xammer.cloud.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AlertsMetadataDto {
    private int totalAlerts;
    private int vpcQuotaCount;
    private int networkQuotaCount;
    private int costAnomalyCount;
    private int securityAlertCount;
    private long fetchDurationMs;
    private String timestamp;
    private boolean fromCache;
}
