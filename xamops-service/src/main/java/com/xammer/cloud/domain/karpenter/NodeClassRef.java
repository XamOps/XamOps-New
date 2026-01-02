package com.xammer.cloud.domain.karpenter;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * NodeClassRef: Reference to an EC2NodeClass.
 * Tells Karpenter which EC2 configuration to use.
 * 
 * Example:
 * {
 * "name": "default"
 * }
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NodeClassRef {

    /**
     * Name: The name of the EC2NodeClass to reference.
     * Must be in the same namespace as the NodePool.
     */
    private String name;
}
