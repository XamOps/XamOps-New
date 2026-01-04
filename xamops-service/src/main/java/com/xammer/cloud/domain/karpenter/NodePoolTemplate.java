package com.xammer.cloud.domain.karpenter;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * NodePoolTemplate: The node template section of NodePoolSpec.
 * Defines the characteristics of nodes this pool will provision.
 * 
 * Contains:
 * - metadata (labels, annotations)
 * - spec (requirements, taints, nodeClassRef)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NodePoolTemplate {

    /**
     * metadata: Labels and annotations for provisioned nodes.
     * Example: {"workload-type": "batch"}
     */
    private NodePoolTemplateMetadata metadata;

    /**
     * spec: The core template spec (requirements, taints, nodeClassRef).
     */
    private NodePoolTemplateSpec spec;
}
