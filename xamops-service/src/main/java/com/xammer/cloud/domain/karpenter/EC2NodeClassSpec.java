package com.xammer.cloud.domain.karpenter;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * EC2NodeClassSpec represents the AWS-specific configuration for nodes.
 * Maps to: karpenter.k8s.aws/v1beta1.EC2NodeClass.spec
 * 
 * Example YAML:
 * spec:
 *   subnetSelector:
 *     karpenter.sh/discovery: "my-cluster"
 *   securityGroupSelector:
 *     karpenter.sh/discovery: "my-cluster"
 *   amiFamily: AL2
 *   role: "KarpenterNodeRole-${clusterName}"
 *   tags:
 *     Environment: "staging"
 *     ManagedBy: "karpenter"
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EC2NodeClassSpec {

    /**
     * SubnetSelector: Labels to identify subnets.
     * Maps to nodes launched by Karpenter
     */
    private Map<String, String> subnetSelector;

    /**
     * SecurityGroupSelector: Labels to identify security groups.
     * Maps to nodes launched by Karpenter
     */
    private Map<String, String> securityGroupSelector;

    /**
     * AMIFamily: The base OS image family.
     * Common values: AL2 (Amazon Linux 2), UBUNTU, CUSTOM
     */
    private String amiFamily;

    /**
     * Role: IAM Role name for the EC2 nodes.
     * NOT the service account role; this is for EC2 instances themselves.
     * Example: "KarpenterNodeRole-prod-cluster-1"
     */
    private String role;

    /**
     * Tags: EC2 tags applied to all nodes.
     * Useful for cost allocation and identification.
     */
    private Map<String, String> tags;

    /**
     * BlockDeviceMappings: Custom EBS volume configurations.
     * Optional; Karpenter provides sensible defaults.
     */
    private List<Map<String, Object>> blockDeviceMappings;

    /**
     * MetadataOptions: EC2 Instance Metadata Service (IMDS) settings.
     * Optional; defaults are secure (IMDSv2 enforced).
     */
    private Map<String, Object> metadataOptions;
}
