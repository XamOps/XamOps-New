package com.xammer.cloud.dto.flexcompute;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConfigureCapacityRequest {

    // Required parameter
    @JsonProperty("cidrId")
    private String cidrId; // Policy/capacity ID (e.g., "sc_830349911839")

    // Optional parameters
    @JsonProperty("vpc")
    private String vpc; // VPC identifier (pattern: vpc-* or "default")

    @JsonProperty("securityGroups")
    private String securityGroups; // Security group identifier (pattern: sg-* or "none")

    @JsonProperty("amiImage")
    private String amiImage; // AMI identifier (pattern: ami-* or "default")

    @JsonProperty("sshPairKey")
    private String sshPairKey; // SSH key pair name

    @JsonProperty("ebsVolumeSize")
    private Integer ebsVolumeSize; // Storage capacity in GB (minimum: 1)
}
