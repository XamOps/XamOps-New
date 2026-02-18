package com.xammer.cloud.dto.gcp;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for a GKE (Google Kubernetes Engine) cluster.
 * Matches the frontend GKECluster TypeScript interface.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GkeClusterDto {
    private String name;
    private String location;
    private String zone;
    private String region;
    private String version;
    private boolean connected;
    private String status;
    private Integer nodeCount;
    private String currentNodeVersion;
    private String endpoint;
    private boolean masterAuthorizedNetworksEnabled;
    private String loggingService;
    private String monitoringService;
}
