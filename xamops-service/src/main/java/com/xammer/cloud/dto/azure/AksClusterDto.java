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
    private Integer nodeCount;
    private boolean connected;
}
