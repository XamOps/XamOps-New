package com.xammer.cloud.domain.karpenter;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * NodePoolTemplateMetadata: Metadata labels/annotations for nodes.
 * These are applied to K8s Node resources created by Karpenter.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NodePoolTemplateMetadata {

    /**
     * Labels: K8s labels applied to nodes.
     * Used for pod scheduling (nodeSelectors, affinity).
     * Example: {"tier": "karpenter-managed"}
     */
    private Map<String, String> labels;

    /**
     * Annotations: K8s annotations for metadata.
     * Not used for scheduling, just tracking.
     */
    private Map<String, String> annotations;
}