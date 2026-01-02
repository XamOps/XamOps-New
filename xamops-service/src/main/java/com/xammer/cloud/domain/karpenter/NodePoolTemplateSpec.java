package com.xammer.cloud.domain.karpenter;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * NodePoolTemplateSpec: Core template specification.
 * Defines requirements (capacity type, instance type, zone) and EC2 config
 * reference.
 * 
 * Example YAML:
 * spec:
 * requirements:
 * - key: node.kubernetes.io/capacity-type
 * operator: In
 * values: ["spot", "on-demand"]
 * - key: node.kubernetes.io/instance-type
 * operator: In
 * values: ["t3.medium", "t3.small"]
 * - key: kubernetes.io/arch
 * operator: In
 * values: ["amd64"]
 * nodeClassRef:
 * name: default
 * taints:
 * - key: karpenter.sh/capacity-type
 * value: spot
 * effect: NoSchedule
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NodePoolTemplateSpec {

    /**
     * Requirements: List of node requirements (filters).
     * Karpenter will only provision nodes matching ALL requirements.
     * 
     * Standard keys:
     * - "node.kubernetes.io/capacity-type" → "spot" or "on-demand"
     * - "node.kubernetes.io/instance-type" → instance family (t3, m5, c5, etc)
     * - "kubernetes.io/arch" → amd64, arm64
     * - "topology.kubernetes.io/zone" → AWS zone
     */
    private List<NodePoolRequirement> requirements;

    /**
     * NodeClassRef: Reference to the EC2NodeClass this pool uses.
     * Links to the EC2 configuration (subnet, security group, role, tags).
     * 
     * Example:
     * {
     * "name": "default"
     * }
     * This means look for EC2NodeClass named "default" in same namespace.
     */
    private NodeClassRef nodeClassRef;

    /**
     * Taints: K8s taints applied to provisioned nodes.
     * Prevents pod scheduling unless pod tolerates the taint.
     * Example: taint="karpenter.sh/capacity-type=spot:NoSchedule"
     * means only pods tolerating "spot" can run on these nodes.
     */
    private List<Map<String, Object>> taints;

    /**
     * Expiration: Node expiration behavior.
     * Controls rolling updates and node lifecycle.
     */
    private Map<String, Object> expiration;

    /**
     * Kubelet: Custom kubelet configuration for nodes.
     * Example: maxPods, systemReserved, etc.
     * Usually not needed; Karpenter provides good defaults.
     */
    private Map<String, Object> kubelet;
}
