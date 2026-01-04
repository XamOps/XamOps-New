package com.xammer.cloud.domain.karpenter;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NodePoolSpec {

    private NodePoolTemplate template;

    // Limits (cpu, memory)
    private Map<String, String> limits;

    // Disruption configuration
    private NodePoolDisruption disruption;

    // Weight for ordering
    private Integer weight;

    // TTL settings (older API versions, optional)
    private Long ttlSecondsAfterEmpty;
    private Long ttlSecondsUntilExpired;
}