package com.xammer.cloud.domain.karpenter;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * NodePoolDisruption: Controls when Karpenter can terminate nodes.
 * Prevents disruption during critical operations.
 * 
 * Example:
 * disruption:
 * consolidationPolicy: WhenUnderutilized
 * budgets:
 * - nodes: "10%"
 * duration: 5m
 * expireAfter: 30d
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NodePoolDisruption {

    /**
     * ConsolidationPolicy: When to consolidate nodes.
     * - "WhenUnderutilized": Bin-pack pods to fewer nodes when CPU/memory <
     * threshold
     * - "Never": Don't consolidate (static node pool)
     */
    private String consolidationPolicy;

    /**
     * ExpireAfter: Force node termination after this duration.
     * Good for rolling updates, ensuring fresh AMI, patching.
     * Example: "30d" means replace nodes every 30 days.
     */
    private String expireAfter;

    /**
     * Budgets: Disruption budget constraints.
     * Limit how many nodes Karpenter can disrupt simultaneously.
     * Example: "10%" means max 10% of pool nodes can be disrupted at once.
     */
    private List<Map<String, String>> budgets;

    /**
     * ConsolidateAfter: Time to wait before consolidating.
     * Default: 30s. Prevents thrashing.
     */
    private String consolidateAfter;
}
