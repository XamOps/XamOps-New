package com.xammer.cloud.dto.k8s;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * DTO for Karpenter dashboard data fetched from cluster CRDs.
 * Displays NodePools, EC2NodeClasses, NodeClaims, and controller status.
 */
public class KarpenterDashboard {

    // Controller Status
    private boolean installed;
    private String version;
    private String podStatus;
    private boolean healthy;
    private String iamRoleArn;

    // NodePools
    private List<NodePoolInfo> nodePools = new ArrayList<>();

    // EC2NodeClasses
    private List<EC2NodeClassInfo> ec2NodeClasses = new ArrayList<>();

    // NodeClaims (active provisioning requests)
    private List<NodeClaimInfo> nodeClaims = new ArrayList<>();

    // Summary metrics
    private int totalNodePools;
    private int totalEC2NodeClasses;
    private int totalNodeClaims;
    private int nodesProvisionedByKarpenter;

    // Errors/Warnings from status conditions
    private List<StatusCondition> conditions = new ArrayList<>();

    // Constructors
    public KarpenterDashboard() {
    }

    // Getters and Setters
    public boolean isInstalled() {
        return installed;
    }

    public void setInstalled(boolean installed) {
        this.installed = installed;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getPodStatus() {
        return podStatus;
    }

    public void setPodStatus(String podStatus) {
        this.podStatus = podStatus;
    }

    public boolean isHealthy() {
        return healthy;
    }

    public void setHealthy(boolean healthy) {
        this.healthy = healthy;
    }

    public String getIamRoleArn() {
        return iamRoleArn;
    }

    public void setIamRoleArn(String iamRoleArn) {
        this.iamRoleArn = iamRoleArn;
    }

    public List<NodePoolInfo> getNodePools() {
        return nodePools;
    }

    public void setNodePools(List<NodePoolInfo> nodePools) {
        this.nodePools = nodePools;
    }

    public List<EC2NodeClassInfo> getEc2NodeClasses() {
        return ec2NodeClasses;
    }

    public void setEc2NodeClasses(List<EC2NodeClassInfo> ec2NodeClasses) {
        this.ec2NodeClasses = ec2NodeClasses;
    }

    public List<NodeClaimInfo> getNodeClaims() {
        return nodeClaims;
    }

    public void setNodeClaims(List<NodeClaimInfo> nodeClaims) {
        this.nodeClaims = nodeClaims;
    }

    public int getTotalNodePools() {
        return totalNodePools;
    }

    public void setTotalNodePools(int totalNodePools) {
        this.totalNodePools = totalNodePools;
    }

    public int getTotalEC2NodeClasses() {
        return totalEC2NodeClasses;
    }

    public void setTotalEC2NodeClasses(int totalEC2NodeClasses) {
        this.totalEC2NodeClasses = totalEC2NodeClasses;
    }

    public int getTotalNodeClaims() {
        return totalNodeClaims;
    }

    public void setTotalNodeClaims(int totalNodeClaims) {
        this.totalNodeClaims = totalNodeClaims;
    }

    public int getNodesProvisionedByKarpenter() {
        return nodesProvisionedByKarpenter;
    }

    public void setNodesProvisionedByKarpenter(int nodesProvisionedByKarpenter) {
        this.nodesProvisionedByKarpenter = nodesProvisionedByKarpenter;
    }

    public List<StatusCondition> getConditions() {
        return conditions;
    }

    public void setConditions(List<StatusCondition> conditions) {
        this.conditions = conditions;
    }

    // --- Inner Classes ---

    /**
     * NodePool configuration from karpenter.sh/v1beta1/NodePool
     */
    public static class NodePoolInfo {
        private String name;
        private String cpuLimit;
        private String memoryLimit;
        private String consolidationPolicy;
        private String expireAfter;
        private List<String> capacityTypes = new ArrayList<>();
        private List<String> instanceTypes = new ArrayList<>();
        private List<String> architectures = new ArrayList<>();
        private String nodeClassRef;
        private String createdAt;
        private int disruptionBudgetNodes;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getCpuLimit() {
            return cpuLimit;
        }

        public void setCpuLimit(String cpuLimit) {
            this.cpuLimit = cpuLimit;
        }

        public String getMemoryLimit() {
            return memoryLimit;
        }

        public void setMemoryLimit(String memoryLimit) {
            this.memoryLimit = memoryLimit;
        }

        public String getConsolidationPolicy() {
            return consolidationPolicy;
        }

        public void setConsolidationPolicy(String consolidationPolicy) {
            this.consolidationPolicy = consolidationPolicy;
        }

        public String getExpireAfter() {
            return expireAfter;
        }

        public void setExpireAfter(String expireAfter) {
            this.expireAfter = expireAfter;
        }

        public List<String> getCapacityTypes() {
            return capacityTypes;
        }

        public void setCapacityTypes(List<String> capacityTypes) {
            this.capacityTypes = capacityTypes;
        }

        public List<String> getInstanceTypes() {
            return instanceTypes;
        }

        public void setInstanceTypes(List<String> instanceTypes) {
            this.instanceTypes = instanceTypes;
        }

        public List<String> getArchitectures() {
            return architectures;
        }

        public void setArchitectures(List<String> architectures) {
            this.architectures = architectures;
        }

        public String getNodeClassRef() {
            return nodeClassRef;
        }

        public void setNodeClassRef(String nodeClassRef) {
            this.nodeClassRef = nodeClassRef;
        }

        public String getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(String createdAt) {
            this.createdAt = createdAt;
        }

        public int getDisruptionBudgetNodes() {
            return disruptionBudgetNodes;
        }

        public void setDisruptionBudgetNodes(int disruptionBudgetNodes) {
            this.disruptionBudgetNodes = disruptionBudgetNodes;
        }
    }

    /**
     * EC2NodeClass configuration from karpenter.k8s.aws/v1beta1/EC2NodeClass
     */
    public static class EC2NodeClassInfo {
        private String name;
        private String amiFamily;
        private String role;
        private String instanceProfile;
        private boolean ready;
        private String readyMessage;
        private List<SubnetInfo> subnets = new ArrayList<>();
        private List<SecurityGroupInfo> securityGroups = new ArrayList<>();
        private Map<String, String> tags;
        private String createdAt;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getAmiFamily() {
            return amiFamily;
        }

        public void setAmiFamily(String amiFamily) {
            this.amiFamily = amiFamily;
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public String getInstanceProfile() {
            return instanceProfile;
        }

        public void setInstanceProfile(String instanceProfile) {
            this.instanceProfile = instanceProfile;
        }

        public boolean isReady() {
            return ready;
        }

        public void setReady(boolean ready) {
            this.ready = ready;
        }

        public String getReadyMessage() {
            return readyMessage;
        }

        public void setReadyMessage(String readyMessage) {
            this.readyMessage = readyMessage;
        }

        public List<SubnetInfo> getSubnets() {
            return subnets;
        }

        public void setSubnets(List<SubnetInfo> subnets) {
            this.subnets = subnets;
        }

        public List<SecurityGroupInfo> getSecurityGroups() {
            return securityGroups;
        }

        public void setSecurityGroups(List<SecurityGroupInfo> securityGroups) {
            this.securityGroups = securityGroups;
        }

        public Map<String, String> getTags() {
            return tags;
        }

        public void setTags(Map<String, String> tags) {
            this.tags = tags;
        }

        public String getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(String createdAt) {
            this.createdAt = createdAt;
        }
    }

    /**
     * Subnet information discovered by Karpenter
     */
    public static class SubnetInfo {
        private String id;
        private String zone;
        private String zoneId;

        public SubnetInfo() {
        }

        public SubnetInfo(String id, String zone, String zoneId) {
            this.id = id;
            this.zone = zone;
            this.zoneId = zoneId;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getZone() {
            return zone;
        }

        public void setZone(String zone) {
            this.zone = zone;
        }

        public String getZoneId() {
            return zoneId;
        }

        public void setZoneId(String zoneId) {
            this.zoneId = zoneId;
        }
    }

    /**
     * Security Group information discovered by Karpenter
     */
    public static class SecurityGroupInfo {
        private String id;
        private String name;

        public SecurityGroupInfo() {
        }

        public SecurityGroupInfo(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    /**
     * NodeClaim - represents an active node provisioning request
     */
    public static class NodeClaimInfo {
        private String name;
        private String nodePoolName;
        private String instanceType;
        private String zone;
        private String phase;
        private String nodeName;
        private String capacityType;
        private String createdAt;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getNodePoolName() {
            return nodePoolName;
        }

        public void setNodePoolName(String nodePoolName) {
            this.nodePoolName = nodePoolName;
        }

        public String getInstanceType() {
            return instanceType;
        }

        public void setInstanceType(String instanceType) {
            this.instanceType = instanceType;
        }

        public String getZone() {
            return zone;
        }

        public void setZone(String zone) {
            this.zone = zone;
        }

        public String getPhase() {
            return phase;
        }

        public void setPhase(String phase) {
            this.phase = phase;
        }

        public String getNodeName() {
            return nodeName;
        }

        public void setNodeName(String nodeName) {
            this.nodeName = nodeName;
        }

        public String getCapacityType() {
            return capacityType;
        }

        public void setCapacityType(String capacityType) {
            this.capacityType = capacityType;
        }

        public String getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(String createdAt) {
            this.createdAt = createdAt;
        }
    }

    /**
     * Status condition from Karpenter resources
     */
    public static class StatusCondition {
        private String type;
        private String status;
        private String reason;
        private String message;
        private String lastTransitionTime;
        private String severity; // info, warning, error

        public StatusCondition() {
        }

        public StatusCondition(String type, String status, String reason, String message, String severity) {
            this.type = type;
            this.status = status;
            this.reason = reason;
            this.message = message;
            this.severity = severity;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public String getLastTransitionTime() {
            return lastTransitionTime;
        }

        public void setLastTransitionTime(String lastTransitionTime) {
            this.lastTransitionTime = lastTransitionTime;
        }

        public String getSeverity() {
            return severity;
        }

        public void setSeverity(String severity) {
            this.severity = severity;
        }
    }
}