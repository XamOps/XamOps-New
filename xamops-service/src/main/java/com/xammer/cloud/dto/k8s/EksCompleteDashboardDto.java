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

    // ✅ NEW: Detailed Pod Information
    private List<PodDetail> podDetails;

    // ✅ NEW: Detailed Node Information
    private List<NodeDetail> nodeDetails;

    // ✅ NEW: Pod Metrics for dedicated Pods tab
    private PodMetrics podMetrics;

    // ✅ NEW: Container Metrics for dedicated Containers tab
    private ContainerMetrics containerMetrics;

    // ✅ NEW: Node Metrics for dedicated Nodes tab
    private NodeMetrics nodeMetrics;

    // ✅ NEW: Karpenter Dashboard Data
    private KarpenterDashboard karpenter;

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

    public PodMetrics getPodMetrics() {
        return podMetrics;
    }

    public void setPodMetrics(PodMetrics podMetrics) {
        this.podMetrics = podMetrics;
    }

    public ContainerMetrics getContainerMetrics() {
        return containerMetrics;
    }

    public void setContainerMetrics(ContainerMetrics containerMetrics) {
        this.containerMetrics = containerMetrics;
    }

    public NodeMetrics getNodeMetrics() {
        return nodeMetrics;
    }

    public void setNodeMetrics(NodeMetrics nodeMetrics) {
        this.nodeMetrics = nodeMetrics;
    }

    public KarpenterDashboard getKarpenter() {
        return karpenter;
    }

    public void setKarpenter(KarpenterDashboard karpenter) {
        this.karpenter = karpenter;
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
        private Double cpuUsageMillicores;
        private Double cpuCapacityMillicores;
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

        public Double getCpuUsageMillicores() {
            return cpuUsageMillicores;
        }

        public void setCpuUsageMillicores(Double cpuUsageMillicores) {
            this.cpuUsageMillicores = cpuUsageMillicores;
        }

        public Double getCpuCapacityMillicores() {
            return cpuCapacityMillicores;
        }

        public void setCpuCapacityMillicores(Double cpuCapacityMillicores) {
            this.cpuCapacityMillicores = cpuCapacityMillicores;
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
            private Double cpuUsageMillicores;
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

            public Double getCpuUsageMillicores() {
                return cpuUsageMillicores;
            }

            public void setCpuUsageMillicores(Double cpuUsageMillicores) {
                this.cpuUsageMillicores = cpuUsageMillicores;
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
            private List<ContainerMetrics> containers;
            private List<ContainerStatus> containerStatuses; // NEW: Container lifecycle states from K8s API

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

            public static class ContainerStatus {
                private String name;
                private String state; // "running", "waiting", or "terminated"
                private Integer restartCount;
                private String image;
                private Boolean ready;

                // Getters and Setters
                public String getName() {
                    return name;
                }

                public void setName(String name) {
                    this.name = name;
                }

                public String getState() {
                    return state;
                }

                public void setState(String state) {
                    this.state = state;
                }

                public Integer getRestartCount() {
                    return restartCount;
                }

                public void setRestartCount(Integer restartCount) {
                    this.restartCount = restartCount;
                }

                public String getImage() {
                    return image;
                }

                public void setImage(String image) {
                    this.image = image;
                }

                public Boolean getReady() {
                    return ready;
                }

                public void setReady(Boolean ready) {
                    this.ready = ready;
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

            public List<ContainerMetrics> getContainers() {
                return containers;
            }

            public void setContainers(List<ContainerMetrics> containers) {
                this.containers = containers;
            }

            public List<ContainerStatus> getContainerStatuses() {
                return containerStatuses;
            }

            public void setContainerStatuses(List<ContainerStatus> containerStatuses) {
                this.containerStatuses = containerStatuses;
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

    public List<PodDetail> getPodDetails() {
        return podDetails;
    }

    public void setPodDetails(List<PodDetail> podDetails) {
        this.podDetails = podDetails;
    }

    public List<NodeDetail> getNodeDetails() {
        return nodeDetails;
    }

    public void setNodeDetails(List<NodeDetail> nodeDetails) {
        this.nodeDetails = nodeDetails;
    }

    /**
     * ✅ NEW: Comprehensive Pod Details with Container-level Metrics
     * This provides Cast.ai-level detail for individual pods
     */
    public static class PodDetail {
        private String name;
        private String namespace;
        private String phase;
        private String node;
        private String podIp;
        private Boolean ready;
        private Integer restarts;
        private String age;
        private String ownerKind;
        private String ownerName;
        private Double cpuRequest;
        private Double memoryRequest;
        private Double cpuLimit;
        private Double memoryLimit;
        private Double cpuUsage;
        private Double memoryUsage;
        private Integer containerCount;
        private List<ContainerDetail> containers;

        public static class ContainerDetail {
            private String name;
            private String image;
            private String imageId;
            private Double cpuUsage;
            private Double memoryUsage;
            private Double fsUsage;
            private Boolean running;
            private Integer restarts;

            // Getters and Setters
            public String getName() {
                return name;
            }

            public void setName(String name) {
                this.name = name;
            }

            public String getImage() {
                return image;
            }

            public void setImage(String image) {
                this.image = image;
            }

            public String getImageId() {
                return imageId;
            }

            public void setImageId(String imageId) {
                this.imageId = imageId;
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

            public Boolean getRunning() {
                return running;
            }

            public void setRunning(Boolean running) {
                this.running = running;
            }

            public Integer getRestarts() {
                return restarts;
            }

            public void setRestarts(Integer restarts) {
                this.restarts = restarts;
            }
        }

        // Getters and Setters for PodDetail
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

        public String getPodIp() {
            return podIp;
        }

        public void setPodIp(String podIp) {
            this.podIp = podIp;
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

        public String getAge() {
            return age;
        }

        public void setAge(String age) {
            this.age = age;
        }

        public String getOwnerKind() {
            return ownerKind;
        }

        public void setOwnerKind(String ownerKind) {
            this.ownerKind = ownerKind;
        }

        public String getOwnerName() {
            return ownerName;
        }

        public void setOwnerName(String ownerName) {
            this.ownerName = ownerName;
        }

        public Double getCpuRequest() {
            return cpuRequest;
        }

        public void setCpuRequest(Double cpuRequest) {
            this.cpuRequest = cpuRequest;
        }

        public Double getMemoryRequest() {
            return memoryRequest;
        }

        public void setMemoryRequest(Double memoryRequest) {
            this.memoryRequest = memoryRequest;
        }

        public Double getCpuLimit() {
            return cpuLimit;
        }

        public void setCpuLimit(Double cpuLimit) {
            this.cpuLimit = cpuLimit;
        }

        public Double getMemoryLimit() {
            return memoryLimit;
        }

        public void setMemoryLimit(Double memoryLimit) {
            this.memoryLimit = memoryLimit;
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

        public Integer getContainerCount() {
            return containerCount;
        }

        public void setContainerCount(Integer containerCount) {
            this.containerCount = containerCount;
        }

        public List<ContainerDetail> getContainers() {
            return containers;
        }

        public void setContainers(List<ContainerDetail> containers) {
            this.containers = containers;
        }
    }

    /**
     * ✅ NEW: Comprehensive Node Details with Capacity, Conditions, and Pod
     * Placement
     * This provides Cast.ai-level detail for individual nodes
     */
    public static class NodeDetail {
        private String name;
        private String internalIp;
        private String hostname;
        private String status;
        private String kubeletVersion;
        private String osImage;
        private String kernelVersion;
        private String containerRuntime;
        private String instanceId;
        private String zone;
        private Double cpuCapacity;
        private Double memoryCapacity;
        private Integer podsCapacity;
        private Double cpuAllocatable;
        private Double memoryAllocatable;
        private Integer podsAllocatable;
        private Double cpuUsagePercent;
        private Double cpuUsageMillicores;
        private Double memoryUsagePercent;
        private List<String> conditions;
        private List<String> pods;
        private Integer podCount;

        // Getters and Setters
        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getInternalIp() {
            return internalIp;
        }

        public void setInternalIp(String internalIp) {
            this.internalIp = internalIp;
        }

        public String getHostname() {
            return hostname;
        }

        public void setHostname(String hostname) {
            this.hostname = hostname;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public String getKubeletVersion() {
            return kubeletVersion;
        }

        public void setKubeletVersion(String kubeletVersion) {
            this.kubeletVersion = kubeletVersion;
        }

        public String getOsImage() {
            return osImage;
        }

        public void setOsImage(String osImage) {
            this.osImage = osImage;
        }

        public String getKernelVersion() {
            return kernelVersion;
        }

        public void setKernelVersion(String kernelVersion) {
            this.kernelVersion = kernelVersion;
        }

        public String getContainerRuntime() {
            return containerRuntime;
        }

        public void setContainerRuntime(String containerRuntime) {
            this.containerRuntime = containerRuntime;
        }

        public String getInstanceId() {
            return instanceId;
        }

        public void setInstanceId(String instanceId) {
            this.instanceId = instanceId;
        }

        public String getZone() {
            return zone;
        }

        public void setZone(String zone) {
            this.zone = zone;
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

        public Integer getPodsCapacity() {
            return podsCapacity;
        }

        public void setPodsCapacity(Integer podsCapacity) {
            this.podsCapacity = podsCapacity;
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

        public Integer getPodsAllocatable() {
            return podsAllocatable;
        }

        public void setPodsAllocatable(Integer podsAllocatable) {
            this.podsAllocatable = podsAllocatable;
        }

        public Double getCpuUsagePercent() {
            return cpuUsagePercent;
        }

        public void setCpuUsagePercent(Double cpuUsagePercent) {
            this.cpuUsagePercent = cpuUsagePercent;
        }

        public Double getCpuUsageMillicores() {
            return cpuUsageMillicores;
        }

        public void setCpuUsageMillicores(Double cpuUsageMillicores) {
            this.cpuUsageMillicores = cpuUsageMillicores;
        }

        public Double getMemoryUsagePercent() {
            return memoryUsagePercent;
        }

        public void setMemoryUsagePercent(Double memoryUsagePercent) {
            this.memoryUsagePercent = memoryUsagePercent;
        }

        public List<String> getConditions() {
            return conditions;
        }

        public void setConditions(List<String> conditions) {
            this.conditions = conditions;
        }

        public List<String> getPods() {
            return pods;
        }

        public void setPods(List<String> pods) {
            this.pods = pods;
        }

        public Integer getPodCount() {
            return podCount;
        }

        public void setPodCount(Integer podCount) {
            this.podCount = podCount;
        }
    }

    /**
     * ✅ NEW: Pod Metrics for dedicated Pods tab
     * Comprehensive pod-level aggregated metrics
     */
    public static class PodMetrics {
        private Integer runningCount;
        private Integer pendingCount;
        private Integer failedCount;
        private Integer succeededCount;
        private Integer totalContainers;
        private Integer qosGuaranteed;
        private Integer qosBurstable;
        private Integer qosBestEffort;
        private List<PodRestartInfo> podsWithRestarts;

        // Resource aggregations for charts
        private Double totalCpuRequested;
        private Double totalCpuLimited;
        private Double totalCpuUsed;
        private Double totalMemoryRequested;
        private Double totalMemoryLimited;
        private Double totalMemoryUsed;

        public static class PodRestartInfo {
            private String name;
            private String namespace;
            private Integer restarts;
            private String lastRestartTime;
            private String status;

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

            public Integer getRestarts() {
                return restarts;
            }

            public void setRestarts(Integer restarts) {
                this.restarts = restarts;
            }

            public String getLastRestartTime() {
                return lastRestartTime;
            }

            public void setLastRestartTime(String lastRestartTime) {
                this.lastRestartTime = lastRestartTime;
            }

            public String getStatus() {
                return status;
            }

            public void setStatus(String status) {
                this.status = status;
            }
        }

        // Getters and Setters
        public Integer getRunningCount() {
            return runningCount;
        }

        public void setRunningCount(Integer runningCount) {
            this.runningCount = runningCount;
        }

        public Integer getPendingCount() {
            return pendingCount;
        }

        public void setPendingCount(Integer pendingCount) {
            this.pendingCount = pendingCount;
        }

        public Integer getFailedCount() {
            return failedCount;
        }

        public void setFailedCount(Integer failedCount) {
            this.failedCount = failedCount;
        }

        public Integer getSucceededCount() {
            return succeededCount;
        }

        public void setSucceededCount(Integer succeededCount) {
            this.succeededCount = succeededCount;
        }

        public Integer getTotalContainers() {
            return totalContainers;
        }

        public void setTotalContainers(Integer totalContainers) {
            this.totalContainers = totalContainers;
        }

        public Integer getQosGuaranteed() {
            return qosGuaranteed;
        }

        public void setQosGuaranteed(Integer qosGuaranteed) {
            this.qosGuaranteed = qosGuaranteed;
        }

        public Integer getQosBurstable() {
            return qosBurstable;
        }

        public void setQosBurstable(Integer qosBurstable) {
            this.qosBurstable = qosBurstable;
        }

        public Integer getQosBestEffort() {
            return qosBestEffort;
        }

        public void setQosBestEffort(Integer qosBestEffort) {
            this.qosBestEffort = qosBestEffort;
        }

        public List<PodRestartInfo> getPodsWithRestarts() {
            return podsWithRestarts;
        }

        public void setPodsWithRestarts(List<PodRestartInfo> podsWithRestarts) {
            this.podsWithRestarts = podsWithRestarts;
        }

        public Double getTotalCpuRequested() {
            return totalCpuRequested;
        }

        public void setTotalCpuRequested(Double totalCpuRequested) {
            this.totalCpuRequested = totalCpuRequested;
        }

        public Double getTotalCpuLimited() {
            return totalCpuLimited;
        }

        public void setTotalCpuLimited(Double totalCpuLimited) {
            this.totalCpuLimited = totalCpuLimited;
        }

        public Double getTotalCpuUsed() {
            return totalCpuUsed;
        }

        public void setTotalCpuUsed(Double totalCpuUsed) {
            this.totalCpuUsed = totalCpuUsed;
        }

        public Double getTotalMemoryRequested() {
            return totalMemoryRequested;
        }

        public void setTotalMemoryRequested(Double totalMemoryRequested) {
            this.totalMemoryRequested = totalMemoryRequested;
        }

        public Double getTotalMemoryLimited() {
            return totalMemoryLimited;
        }

        public void setTotalMemoryLimited(Double totalMemoryLimited) {
            this.totalMemoryLimited = totalMemoryLimited;
        }

        public Double getTotalMemoryUsed() {
            return totalMemoryUsed;
        }

        public void setTotalMemoryUsed(Double totalMemoryUsed) {
            this.totalMemoryUsed = totalMemoryUsed;
        }
    }

    /**
     * ✅ NEW: Container Metrics for dedicated Containers tab
     * Container-level aggregated metrics and detailed list
     */
    public static class ContainerMetrics {
        private Integer runningCount;
        private Integer waitingCount;
        private Integer terminatedCount;
        private Integer totalRestarts;
        private List<ContainerInfo> containers;
        private Double totalMemoryWorkingSet;
        private Double totalMemoryRss;
        private Double totalMemoryAvailable;

        public static class ContainerInfo {
            private String id;
            private String name;
            private String pod;
            private String image;
            private Double cpuUsage;
            private Double memoryUsage;
            private Double fsUsage;
            private Boolean running;

            // Getters and Setters
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

            public String getPod() {
                return pod;
            }

            public void setPod(String pod) {
                this.pod = pod;
            }

            public String getImage() {
                return image;
            }

            public void setImage(String image) {
                this.image = image;
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

            public Boolean getRunning() {
                return running;
            }

            public void setRunning(Boolean running) {
                this.running = running;
            }
        }

        // Getters and Setters
        public Integer getRunningCount() {
            return runningCount;
        }

        public void setRunningCount(Integer runningCount) {
            this.runningCount = runningCount;
        }

        public Integer getWaitingCount() {
            return waitingCount;
        }

        public void setWaitingCount(Integer waitingCount) {
            this.waitingCount = waitingCount;
        }

        public Integer getTerminatedCount() {
            return terminatedCount;
        }

        public void setTerminatedCount(Integer terminatedCount) {
            this.terminatedCount = terminatedCount;
        }

        public Integer getTotalRestarts() {
            return totalRestarts;
        }

        public void setTotalRestarts(Integer totalRestarts) {
            this.totalRestarts = totalRestarts;
        }

        public List<ContainerInfo> getContainers() {
            return containers;
        }

        public void setContainers(List<ContainerInfo> containers) {
            this.containers = containers;
        }

        public Double getTotalMemoryWorkingSet() {
            return totalMemoryWorkingSet;
        }

        public void setTotalMemoryWorkingSet(Double totalMemoryWorkingSet) {
            this.totalMemoryWorkingSet = totalMemoryWorkingSet;
        }

        public Double getTotalMemoryRss() {
            return totalMemoryRss;
        }

        public void setTotalMemoryRss(Double totalMemoryRss) {
            this.totalMemoryRss = totalMemoryRss;
        }

        public Double getTotalMemoryAvailable() {
            return totalMemoryAvailable;
        }

        public void setTotalMemoryAvailable(Double totalMemoryAvailable) {
            this.totalMemoryAvailable = totalMemoryAvailable;
        }
    }

    /**
     * ✅ NEW: Node Metrics for dedicated Nodes tab
     * Node-level aggregated metrics and detailed list
     */
    public static class NodeMetrics {
        private Integer readyCount;
        private Double totalCpuCapacity;
        private Double totalMemoryCapacity;
        private Integer totalPods;
        private Integer conditionsReady;
        private Integer conditionsDiskPressure;
        private Integer conditionsMemoryPressure;
        private Integer conditionsPidPressure;
        private List<NodeInfo> nodes;

        public static class NodeInfo {
            private String name;
            private String internalIp;
            private Boolean ready;
            private Double cpuCapacity;
            private Double cpuAllocatable;
            private Double cpuUsage;
            private Double cpuUsageMillicores;
            private Double memoryCapacity;
            private Double memoryAllocatable;
            private Double memoryUsage;
            private Integer podCapacity;
            private Integer podCount;
            private String kubeletVersion;
            private String zone;
            private String instanceId;

            // Getters and Setters
            public String getName() {
                return name;
            }

            public void setName(String name) {
                this.name = name;
            }

            public String getInternalIp() {
                return internalIp;
            }

            public void setInternalIp(String internalIp) {
                this.internalIp = internalIp;
            }

            public Boolean getReady() {
                return ready;
            }

            public void setReady(Boolean ready) {
                this.ready = ready;
            }

            public Double getCpuCapacity() {
                return cpuCapacity;
            }

            public void setCpuCapacity(Double cpuCapacity) {
                this.cpuCapacity = cpuCapacity;
            }

            public Double getCpuAllocatable() {
                return cpuAllocatable;
            }

            public void setCpuAllocatable(Double cpuAllocatable) {
                this.cpuAllocatable = cpuAllocatable;
            }

            public Double getCpuUsage() {
                return cpuUsage;
            }

            public void setCpuUsage(Double cpuUsage) {
                this.cpuUsage = cpuUsage;
            }

            public Double getCpuUsageMillicores() {
                return cpuUsageMillicores;
            }

            public void setCpuUsageMillicores(Double cpuUsageMillicores) {
                this.cpuUsageMillicores = cpuUsageMillicores;
            }

            public Double getMemoryCapacity() {
                return memoryCapacity;
            }

            public void setMemoryCapacity(Double memoryCapacity) {
                this.memoryCapacity = memoryCapacity;
            }

            public Double getMemoryAllocatable() {
                return memoryAllocatable;
            }

            public void setMemoryAllocatable(Double memoryAllocatable) {
                this.memoryAllocatable = memoryAllocatable;
            }

            public Double getMemoryUsage() {
                return memoryUsage;
            }

            public void setMemoryUsage(Double memoryUsage) {
                this.memoryUsage = memoryUsage;
            }

            public Integer getPodCapacity() {
                return podCapacity;
            }

            public void setPodCapacity(Integer podCapacity) {
                this.podCapacity = podCapacity;
            }

            public Integer getPodCount() {
                return podCount;
            }

            public void setPodCount(Integer podCount) {
                this.podCount = podCount;
            }

            public String getKubeletVersion() {
                return kubeletVersion;
            }

            public void setKubeletVersion(String kubeletVersion) {
                this.kubeletVersion = kubeletVersion;
            }

            public String getZone() {
                return zone;
            }

            public void setZone(String zone) {
                this.zone = zone;
            }

            public String getInstanceId() {
                return instanceId;
            }

            public void setInstanceId(String instanceId) {
                this.instanceId = instanceId;
            }
        }

        // Getters and Setters
        public Integer getReadyCount() {
            return readyCount;
        }

        public void setReadyCount(Integer readyCount) {
            this.readyCount = readyCount;
        }

        public Double getTotalCpuCapacity() {
            return totalCpuCapacity;
        }

        public void setTotalCpuCapacity(Double totalCpuCapacity) {
            this.totalCpuCapacity = totalCpuCapacity;
        }

        public Double getTotalMemoryCapacity() {
            return totalMemoryCapacity;
        }

        public void setTotalMemoryCapacity(Double totalMemoryCapacity) {
            this.totalMemoryCapacity = totalMemoryCapacity;
        }

        public Integer getTotalPods() {
            return totalPods;
        }

        public void setTotalPods(Integer totalPods) {
            this.totalPods = totalPods;
        }

        public Integer getConditionsReady() {
            return conditionsReady;
        }

        public void setConditionsReady(Integer conditionsReady) {
            this.conditionsReady = conditionsReady;
        }

        public Integer getConditionsDiskPressure() {
            return conditionsDiskPressure;
        }

        public void setConditionsDiskPressure(Integer conditionsDiskPressure) {
            this.conditionsDiskPressure = conditionsDiskPressure;
        }

        public Integer getConditionsMemoryPressure() {
            return conditionsMemoryPressure;
        }

        public void setConditionsMemoryPressure(Integer conditionsMemoryPressure) {
            this.conditionsMemoryPressure = conditionsMemoryPressure;
        }

        public Integer getConditionsPidPressure() {
            return conditionsPidPressure;
        }

        public void setConditionsPidPressure(Integer conditionsPidPressure) {
            this.conditionsPidPressure = conditionsPidPressure;
        }

        public List<NodeInfo> getNodes() {
            return nodes;
        }

        public void setNodes(List<NodeInfo> nodes) {
            this.nodes = nodes;
        }
    }
}