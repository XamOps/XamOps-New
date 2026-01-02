package com.xammer.cloud.dto.k8s;

import java.util.List;
import java.util.Map;

public class EksCompleteDashboardDto {

    // Cluster Overview
    private ClusterOverview clusterOverview;

    // Cost Data
    private CostMetrics costMetrics;

    // Security Data
    private SecurityMetrics securityMetrics;

    // Resource Utilization
    private ResourceUtilization resourceUtilization;

    // Workload Health
    private WorkloadHealth workloadHealth;

    // Kubescape Data
    private KubescapeDashboard kubescape;

    // Trivy Data
    private TrivyDashboard trivy;

    public KubescapeDashboard getKubescape() {
        return kubescape;
    }

    public void setKubescape(KubescapeDashboard kubescape) {
        this.kubescape = kubescape;
    }

    public TrivyDashboard getTrivy() {
        return trivy;
    }

    public void setTrivy(TrivyDashboard trivy) {
        this.trivy = trivy;
    }

    // Nested Classes

    public static class ClusterOverview {
        private String clusterName;
        private String region;
        private String version;
        private String status;
        private Integer totalNodes;
        private Integer totalPods;
        private Integer totalNamespaces;
        private Double cpuUsagePercent;
        private Double memoryUsagePercent;

        // Getters and Setters
        public String getClusterName() {
            return clusterName;
        }

        public void setClusterName(String clusterName) {
            this.clusterName = clusterName;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public Integer getTotalNodes() {
            return totalNodes;
        }

        public void setTotalNodes(Integer totalNodes) {
            this.totalNodes = totalNodes;
        }

        public Integer getTotalPods() {
            return totalPods;
        }

        public void setTotalPods(Integer totalPods) {
            this.totalPods = totalPods;
        }

        public Integer getTotalNamespaces() {
            return totalNamespaces;
        }

        public void setTotalNamespaces(Integer totalNamespaces) {
            this.totalNamespaces = totalNamespaces;
        }

        public Double getCpuUsagePercent() {
            return cpuUsagePercent;
        }

        public void setCpuUsagePercent(Double cpuUsagePercent) {
            this.cpuUsagePercent = cpuUsagePercent;
        }

        public Double getMemoryUsagePercent() {
            return memoryUsagePercent;
        }

        public void setMemoryUsagePercent(Double memoryUsagePercent) {
            this.memoryUsagePercent = memoryUsagePercent;
        }
    }

    public static class CostMetrics {
        private Double dailyCost;
        private Double monthlyCost;
        private List<NodeCost> nodeCosts;

        public static class NodeCost {
            private String nodeName;
            private Double hourlyRate;
            private Double dailyCost;
            private String instanceType;

            public NodeCost(String nodeName, Double hourlyRate, Double dailyCost, String instanceType) {
                this.nodeName = nodeName;
                this.hourlyRate = hourlyRate;
                this.dailyCost = dailyCost;
                this.instanceType = instanceType;
            }

            // Getters and Setters
            public String getNodeName() {
                return nodeName;
            }

            public void setNodeName(String nodeName) {
                this.nodeName = nodeName;
            }

            public Double getHourlyRate() {
                return hourlyRate;
            }

            public void setHourlyRate(Double hourlyRate) {
                this.hourlyRate = hourlyRate;
            }

            public Double getDailyCost() {
                return dailyCost;
            }

            public void setDailyCost(Double dailyCost) {
                this.dailyCost = dailyCost;
            }

            public String getInstanceType() {
                return instanceType;
            }

            public void setInstanceType(String instanceType) {
                this.instanceType = instanceType;
            }
        }

        // Getters and Setters
        public Double getDailyCost() {
            return dailyCost;
        }

        public void setDailyCost(Double dailyCost) {
            this.dailyCost = dailyCost;
        }

        public Double getMonthlyCost() {
            return monthlyCost;
        }

        public void setMonthlyCost(Double monthlyCost) {
            this.monthlyCost = monthlyCost;
        }

        public List<NodeCost> getNodeCosts() {
            return nodeCosts;
        }

        public void setNodeCosts(List<NodeCost> nodeCosts) {
            this.nodeCosts = nodeCosts;
        }
    }

    public static class SecurityMetrics {
        private Long totalEvents;
        private Long eventsLastHour;
        private Integer alertTypes;
        private Map<String, Integer> eventsByPriority;
        private List<SecurityAlert> alerts;

        public static class SecurityAlert {
            private String rule;
            private String priority;
            private String pod;
            private String namespace;
            private Long count;

            public SecurityAlert(String rule, String priority, String pod, String namespace, Long count) {
                this.rule = rule;
                this.priority = priority;
                this.pod = pod;
                this.namespace = namespace;
                this.count = count;
            }

            // Getters and Setters
            public String getRule() {
                return rule;
            }

            public void setRule(String rule) {
                this.rule = rule;
            }

            public String getPriority() {
                return priority;
            }

            public void setPriority(String priority) {
                this.priority = priority;
            }

            public String getPod() {
                return pod;
            }

            public void setPod(String pod) {
                this.pod = pod;
            }

            public String getNamespace() {
                return namespace;
            }

            public void setNamespace(String namespace) {
                this.namespace = namespace;
            }

            public Long getCount() {
                return count;
            }

            public void setCount(Long count) {
                this.count = count;
            }
        }

        // Getters and Setters
        public Long getTotalEvents() {
            return totalEvents;
        }

        public void setTotalEvents(Long totalEvents) {
            this.totalEvents = totalEvents;
        }

        public Long getEventsLastHour() {
            return eventsLastHour;
        }

        public void setEventsLastHour(Long eventsLastHour) {
            this.eventsLastHour = eventsLastHour;
        }

        public Integer getAlertTypes() {
            return alertTypes;
        }

        public void setAlertTypes(Integer alertTypes) {
            this.alertTypes = alertTypes;
        }

        public Map<String, Integer> getEventsByPriority() {
            return eventsByPriority;
        }

        public void setEventsByPriority(Map<String, Integer> eventsByPriority) {
            this.eventsByPriority = eventsByPriority;
        }

        public List<SecurityAlert> getAlerts() {
            return alerts;
        }

        public void setAlerts(List<SecurityAlert> alerts) {
            this.alerts = alerts;
        }
    }

    public static class ResourceUtilization {
        private List<NodeResource> nodes;
        private Map<String, NamespaceResource> namespaces;

        public static class NodeResource {
            private String nodeName;
            private String instanceType;
            private String zone;
            private String condition;
            private Double cpuUsagePercent;
            private Double memoryUsagePercent;
            private Double cpuCapacity;
            private Double memoryCapacity;
            private Double cpuAllocatable;
            private Double memoryAllocatable;
            private Double podsCapacity;
            private Double podsAllocatable;
            private Integer runningPods;
            private Double hourlyCost;

            // Getters and Setters
            public String getNodeName() {
                return nodeName;
            }

            public void setNodeName(String nodeName) {
                this.nodeName = nodeName;
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

            public String getCondition() {
                return condition;
            }

            public void setCondition(String condition) {
                this.condition = condition;
            }

            public Double getCpuUsagePercent() {
                return cpuUsagePercent;
            }

            public void setCpuUsagePercent(Double cpuUsagePercent) {
                this.cpuUsagePercent = cpuUsagePercent;
            }

            public Double getMemoryUsagePercent() {
                return memoryUsagePercent;
            }

            public void setMemoryUsagePercent(Double memoryUsagePercent) {
                this.memoryUsagePercent = memoryUsagePercent;
            }

            public Double getCpuCapacity() {
                return cpuCapacity;
            }

            public void setCpuCapacity(Double cpuCapacity) {
                this.cpuCapacity = cpuCapacity;
            }

            public Double getMemoryCapacity() {
                return memoryCapacity;
            }

            public void setMemoryCapacity(Double memoryCapacity) {
                this.memoryCapacity = memoryCapacity;
            }

            public Double getCpuAllocatable() {
                return cpuAllocatable;
            }

            public void setCpuAllocatable(Double cpuAllocatable) {
                this.cpuAllocatable = cpuAllocatable;
            }

            public Double getMemoryAllocatable() {
                return memoryAllocatable;
            }

            public void setMemoryAllocatable(Double memoryAllocatable) {
                this.memoryAllocatable = memoryAllocatable;
            }

            public Double getPodsCapacity() {
                return podsCapacity;
            }

            public void setPodsCapacity(Double podsCapacity) {
                this.podsCapacity = podsCapacity;
            }

            public Double getPodsAllocatable() {
                return podsAllocatable;
            }

            public void setPodsAllocatable(Double podsAllocatable) {
                this.podsAllocatable = podsAllocatable;
            }

            public Integer getRunningPods() {
                return runningPods;
            }

            public void setRunningPods(Integer runningPods) {
                this.runningPods = runningPods;
            }

            public Double getHourlyCost() {
                return hourlyCost;
            }

            public void setHourlyCost(Double hourlyCost) {
                this.hourlyCost = hourlyCost;
            }
        }

        public static class NamespaceResource {
            private String name;
            private String phase;
            private Integer podCount;
            private Integer deploymentCount;
            private Integer daemonsetCount;
            private Double totalCpuUsage;
            private Double totalMemoryUsage;

            // Getters and Setters
            public String getName() {
                return name;
            }

            public void setName(String name) {
                this.name = name;
            }

            public String getPhase() {
                return phase;
            }

            public void setPhase(String phase) {
                this.phase = phase;
            }

            public Integer getPodCount() {
                return podCount;
            }

            public void setPodCount(Integer podCount) {
                this.podCount = podCount;
            }

            public Integer getDeploymentCount() {
                return deploymentCount;
            }

            public void setDeploymentCount(Integer deploymentCount) {
                this.deploymentCount = deploymentCount;
            }

            public Integer getDaemonsetCount() {
                return daemonsetCount;
            }

            public void setDaemonsetCount(Integer daemonsetCount) {
                this.daemonsetCount = daemonsetCount;
            }

            public Double getTotalCpuUsage() {
                return totalCpuUsage;
            }

            public void setTotalCpuUsage(Double totalCpuUsage) {
                this.totalCpuUsage = totalCpuUsage;
            }

            public Double getTotalMemoryUsage() {
                return totalMemoryUsage;
            }

            public void setTotalMemoryUsage(Double totalMemoryUsage) {
                this.totalMemoryUsage = totalMemoryUsage;
            }
        }

        // Getters and Setters
        public List<NodeResource> getNodes() {
            return nodes;
        }

        public void setNodes(List<NodeResource> nodes) {
            this.nodes = nodes;
        }

        public Map<String, NamespaceResource> getNamespaces() {
            return namespaces;
        }

        public void setNamespaces(Map<String, NamespaceResource> namespaces) {
            this.namespaces = namespaces;
        }
    }

    public static class WorkloadHealth {
        private List<PodHealth> pods;
        private List<DeploymentHealth> deployments;
        private List<DaemonsetHealth> daemonsets;

        public static class PodHealth {
            private String name;
            private String namespace;
            private String phase;
            private String node;
            private Boolean ready;
            private Integer restarts;
            private Double cpuUsage;
            private Double memoryUsage;
            private Map<String, ContainerMetrics> containers;

            public static class ContainerMetrics {
                private String name;
                private Double cpuUsage;
                private Double memoryUsage;
                private Double fsUsage;

                // Getters and Setters
                public String getName() {
                    return name;
                }

                public void setName(String name) {
                    this.name = name;
                }

                public Double getCpuUsage() {
                    return cpuUsage;
                }

                public void setCpuUsage(Double cpuUsage) {
                    this.cpuUsage = cpuUsage;
                }

                public Double getMemoryUsage() {
                    return memoryUsage;
                }

                public void setMemoryUsage(Double memoryUsage) {
                    this.memoryUsage = memoryUsage;
                }

                public Double getFsUsage() {
                    return fsUsage;
                }

                public void setFsUsage(Double fsUsage) {
                    this.fsUsage = fsUsage;
                }
            }

            // Getters and Setters
            public String getName() {
                return name;
            }

            public void setName(String name) {
                this.name = name;
            }

            public String getNamespace() {
                return namespace;
            }

            public void setNamespace(String namespace) {
                this.namespace = namespace;
            }

            public String getPhase() {
                return phase;
            }

            public void setPhase(String phase) {
                this.phase = phase;
            }

            public String getNode() {
                return node;
            }

            public void setNode(String node) {
                this.node = node;
            }

            public Boolean getReady() {
                return ready;
            }

            public void setReady(Boolean ready) {
                this.ready = ready;
            }

            public Integer getRestarts() {
                return restarts;
            }

            public void setRestarts(Integer restarts) {
                this.restarts = restarts;
            }

            public Double getCpuUsage() {
                return cpuUsage;
            }

            public void setCpuUsage(Double cpuUsage) {
                this.cpuUsage = cpuUsage;
            }

            public Double getMemoryUsage() {
                return memoryUsage;
            }

            public void setMemoryUsage(Double memoryUsage) {
                this.memoryUsage = memoryUsage;
            }

            public Map<String, ContainerMetrics> getContainers() {
                return containers;
            }

            public void setContainers(Map<String, ContainerMetrics> containers) {
                this.containers = containers;
            }
        }

        public static class DeploymentHealth {
            private String name;
            private String namespace;
            private Integer desired;
            private Integer available;
            private Integer unavailable;
            private Integer updated;
            private Double healthScore;

            // Getters and Setters
            public String getName() {
                return name;
            }

            public void setName(String name) {
                this.name = name;
            }

            public String getNamespace() {
                return namespace;
            }

            public void setNamespace(String namespace) {
                this.namespace = namespace;
            }

            public Integer getDesired() {
                return desired;
            }

            public void setDesired(Integer desired) {
                this.desired = desired;
            }

            public Integer getAvailable() {
                return available;
            }

            public void setAvailable(Integer available) {
                this.available = available;
            }

            public Integer getUnavailable() {
                return unavailable;
            }

            public void setUnavailable(Integer unavailable) {
                this.unavailable = unavailable;
            }

            public Integer getUpdated() {
                return updated;
            }

            public void setUpdated(Integer updated) {
                this.updated = updated;
            }

            public Double getHealthScore() {
                return healthScore;
            }

            public void setHealthScore(Double healthScore) {
                this.healthScore = healthScore;
            }
        }

        public static class DaemonsetHealth {
            private String name;
            private String namespace;
            private Integer desired;
            private Integer ready;
            private Integer unavailable;
            private Double healthScore;

            // Getters and Setters
            public String getName() {
                return name;
            }

            public void setName(String name) {
                this.name = name;
            }

            public String getNamespace() {
                return namespace;
            }

            public void setNamespace(String namespace) {
                this.namespace = namespace;
            }

            public Integer getDesired() {
                return desired;
            }

            public void setDesired(Integer desired) {
                this.desired = desired;
            }

            public Integer getReady() {
                return ready;
            }

            public void setReady(Integer ready) {
                this.ready = ready;
            }

            public Integer getUnavailable() {
                return unavailable;
            }

            public void setUnavailable(Integer unavailable) {
                this.unavailable = unavailable;
            }

            public Double getHealthScore() {
                return healthScore;
            }

            public void setHealthScore(Double healthScore) {
                this.healthScore = healthScore;
            }
        }

        // Getters and Setters
        public List<PodHealth> getPods() {
            return pods;
        }

        public void setPods(List<PodHealth> pods) {
            this.pods = pods;
        }

        public List<DeploymentHealth> getDeployments() {
            return deployments;
        }

        public void setDeployments(List<DeploymentHealth> deployments) {
            this.deployments = deployments;
        }

        public List<DaemonsetHealth> getDaemonsets() {
            return daemonsets;
        }

        public void setDaemonsets(List<DaemonsetHealth> daemonsets) {
            this.daemonsets = daemonsets;
        }
    }

    // Main DTO Getters and Setters
    public ClusterOverview getClusterOverview() {
        return clusterOverview;
    }

    public void setClusterOverview(ClusterOverview clusterOverview) {
        this.clusterOverview = clusterOverview;
    }

    public CostMetrics getCostMetrics() {
        return costMetrics;
    }

    public void setCostMetrics(CostMetrics costMetrics) {
        this.costMetrics = costMetrics;
    }

    public SecurityMetrics getSecurityMetrics() {
        return securityMetrics;
    }

    public void setSecurityMetrics(SecurityMetrics securityMetrics) {
        this.securityMetrics = securityMetrics;
    }

    public ResourceUtilization getResourceUtilization() {
        return resourceUtilization;
    }

    public void setResourceUtilization(ResourceUtilization resourceUtilization) {
        this.resourceUtilization = resourceUtilization;
    }

    public WorkloadHealth getWorkloadHealth() {
        return workloadHealth;
    }

    public void setWorkloadHealth(WorkloadHealth workloadHealth) {
        this.workloadHealth = workloadHealth;
    }
}