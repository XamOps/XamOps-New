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
    private String vpc; // VPC identifier (pattern: vpc-*)

    @JsonProperty("securityGroup")
    private String securityGroup; // Security group identifier (pattern: sg-*)

    @JsonProperty("amiImage")
    private String amiImage; // AMI identifier (pattern: ami-*)

    @JsonProperty("sshPairKey")
    private String sshPairKey; // SSH key pair name

    @JsonProperty("ebsVolumeSize")
    private Integer ebsVolumeSize; // Storage capacity in GB (minimum: 1)
}
