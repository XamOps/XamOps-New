package com.xammer.cloud.dto.azure;

import com.xammer.cloud.dto.gcp.GkeDashboardDto;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AKS cluster dashboard DTO.
 * Reuses GKE nested DTOs so the JSON shape is identical — all GKE frontend tab
 * components can be used for AKS without modification.
 * Azure-specific addition: resourceGroup field.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AksDashboardDto {

    private String clusterName;
    private String resourceGroup;   // Azure-specific (no GKE equivalent)
    private String location;        // Azure region, e.g. "eastus"
    private String status;
    private String version;
    private boolean connected;
    private LocalDateTime lastSyncedAt;

    // Reuse GKE nested DTOs — same JSON keys, cloud-agnostic Prometheus data
    private GkeDashboardDto.GkeOverviewDto overview;
    private List<GkeDashboardDto.GkeNodePoolDto> nodePools;   // agent pools mapped here
    private GkeDashboardDto.GkeWorkloadHealthDto workloadHealth;
    private GkeDashboardDto.GkeCostMetricsDto costMetrics;
    private GkeDashboardDto.GkeResourceUtilizationDto resourceUtilization;
    private GkeDashboardDto.GkeNodeMetricsDto nodeMetrics;
    private GkeDashboardDto.GkeContainerMetricsDto containerMetrics;
    private GkeDashboardDto.GkeSecurityMetricsDto securityMetrics;
}
