package com.xammer.cloud.dto.k8s;

import java.util.List;
import java.util.Map;

public class KarpenterDashboard {
    private Map<String, Object> metrics;

    // FIXED: Use proper inner class types
    private List<NodeClaim> nodeClaims;
    private List<NodePool> nodePools;

    private Integer totalNodesCreated24h;
    private Integer totalNodesTerminated24h;

    // Constructors
    public KarpenterDashboard() {
    }

    // Getters and Setters
    public Map<String, Object> getMetrics() {
        return metrics;
    }

    public void setMetrics(Map<String, Object> metrics) {
        this.metrics = metrics;
    }

    public List<NodeClaim> getNodeClaims() {
        return nodeClaims;
    }

    public void setNodeClaims(List<NodeClaim> nodeClaims) {
        this.nodeClaims = nodeClaims;
    }

    public List<NodePool> getNodePools() {
        return nodePools;
    }

    public void setNodePools(List<NodePool> nodePools) {
        this.nodePools = nodePools;
    }

    public Integer getTotalNodesCreated24h() {
        return totalNodesCreated24h;
    }

    public void setTotalNodesCreated24h(Integer totalNodesCreated24h) {
        this.totalNodesCreated24h = totalNodesCreated24h;
    }

    public Integer getTotalNodesTerminated24h() {
        return totalNodesTerminated24h;
    }

    public void setTotalNodesTerminated24h(Integer totalNodesTerminated24h) {
        this.totalNodesTerminated24h = totalNodesTerminated24h;
    }

    // --- Inner Classes ---
    public static class NodeClaim {
        private String nodeName;
        private String nodePoolName;
        private String instanceType;
        private String zone;
        private String phase;
        private String age;

        public String getNodeName() {
            return nodeName;
        }

        public void setNodeName(String nodeName) {
            this.nodeName = nodeName;
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

        public String getAge() {
            return age;
        }

        public void setAge(String age) {
            this.age = age;
        }
    }

    public static class NodePool {
        private String poolName;
        private List<String> instanceTypes;
        private Integer cpuLimit;
        private Integer memoryLimitGb;
        private Boolean consolidationEnabled;

        public String getPoolName() {
            return poolName;
        }

        public void setPoolName(String poolName) {
            this.poolName = poolName;
        }

        public List<String> getInstanceTypes() {
            return instanceTypes;
        }

        public void setInstanceTypes(List<String> instanceTypes) {
            this.instanceTypes = instanceTypes;
        }

        public Integer getCpuLimit() {
            return cpuLimit;
        }

        public void setCpuLimit(Integer cpuLimit) {
            this.cpuLimit = cpuLimit;
        }

        public Integer getMemoryLimitGb() {
            return memoryLimitGb;
        }

        public void setMemoryLimitGb(Integer memoryLimitGb) {
            this.memoryLimitGb = memoryLimitGb;
        }

        public Boolean getConsolidationEnabled() {
            return consolidationEnabled;
        }

        public void setConsolidationEnabled(Boolean consolidationEnabled) {
            this.consolidationEnabled = consolidationEnabled;
        }
    }
}