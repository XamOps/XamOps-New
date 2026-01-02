package com.xammer.cloud.domain.karpenter;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * NodePoolRequirement: A single requirement (filter) for node selection.
 * Example:
 * {
 * "key": "node.kubernetes.io/instance-type",
 * "operator": "In",
 * "values": ["t3.medium", "t3.small"]
 * }
 * 
 * Meaning: Karpenter can only use t3.medium or t3.small instances.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NodePoolRequirement {

    /**
     * Key: The requirement key.
     * Examples:
     * - "node.kubernetes.io/instance-type" (instance family)
     * - "node.kubernetes.io/capacity-type" (spot or on-demand)
     * - "kubernetes.io/arch" (cpu architecture)
     * - "topology.kubernetes.io/zone" (AWS availability zone)
     */
    private String key;

    /**
     * Operator: How to match the values.
     * Most common: "In" (value must be in the list)
     * Others: "NotIn", "Gt", "Lt", "GtLt", etc.
     */
    @Builder.Default
    private String operator = "In";

    /**
     * Values: Allowed values for this key.
     * Example: ["t3.medium", "t3.small"]
     * Meaning: only these instance types allowed.
     */
    private List<String> values;
}
