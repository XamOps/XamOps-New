package com.xammer.cloud.dto.azure;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AksClusterDto {
    private String name;
    private String resourceGroup;
    private String location;
    private String kubernetesVersion;
    private String provisioningState;
    /**
     * Azure power state, "Running" or "Stopped". A stopped cluster still reports provisioningState
     * "Succeeded", so this is what tells the two apart.
     */
    private String powerState;
    private Integer nodeCount;
    private boolean connected;
    /** The agent has pushed data before but has not checked in within the last 15 minutes. */
    private boolean agentStale;
}
