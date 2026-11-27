package com.xammer.cloud.dto.azure;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
    /** API server FQDN (the private FQDN for a private cluster). */
    private String endpoint;
    private boolean connected;
    private LocalDateTime lastSyncedAt;

    /**
     * ISO code the cost figures are in: the subscription's billing currency. Absent on rows cached
     * before this field existed, whose figures are still in USD.
     */
    private String currency;

    /** When the XamOps agent last checked in (heartbeat or push). Null when no agent is registered. */
    private LocalDateTime agentLastSeenAt;

    /** The figures come from the agent, but it has not checked in within the last 15 minutes. */
    private boolean agentStale;

    /**
     * Set only on a response, never on a cached row: why this refresh could not produce a complete
     * dashboard, in which case the previous copy is returned instead (or, with none, the partial one).
     */
    private String refreshError;

    /**
     * Why the build is incomplete (a transient Azure or agent-read failure). Never serialized: the
     * sync service reads it to keep the previous cached copy instead of overwriting it.
     */
    @JsonIgnore
    private String partialReason;

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
