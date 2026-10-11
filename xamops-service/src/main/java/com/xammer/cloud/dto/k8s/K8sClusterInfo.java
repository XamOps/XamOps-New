// File: src/main/java/com/xammer/cloud/dto/k8s/K8sClusterInfo.java
package com.xammer.cloud.dto.k8s;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class K8sClusterInfo {
    private String name;
    private String status;
    private String version;
    private String region;
    /** True only when Container Insights is CONFIGURED (an exact log-group match), kept for old consumers. */
    private boolean connected;
    /** Container Insights state with its check time and, when denied, the missing action (design D3). */
    private ContainerInsightsStatusDto containerInsights;
}