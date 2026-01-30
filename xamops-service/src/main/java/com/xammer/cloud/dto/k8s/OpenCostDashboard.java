package com.xammer.cloud.dto.k8s;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenCost Dashboard DTO
 * Contains comprehensive cost allocation data from OpenCost API
 */
public class OpenCostDashboard {

    private Summary summary = new Summary();
    private List<NamespaceAllocation> namespaceAllocations = new ArrayList<>();
    private List<PodAllocation> podAllocations = new ArrayList<>();
    private List<ControllerAllocation> controllerAllocations = new ArrayList<>();
    private List<NodeAllocation> nodeAllocations = new ArrayList<>();
    private List<CostOptimization> optimizations = new ArrayList<>();
    private boolean enabled = false;
    private String lastUpdated;
    private String clusterName;

    // ================================
    // Summary - Aggregated cost metrics
    // ================================
    public static class Summary {
        private Double totalDailyCost = 0.0;
        private Double totalMonthlyCost = 0.0;
        private Double cpuCost = 0.0;
        private Double ramCost = 0.0;
        private Double pvCost = 0.0;
        private Double networkCost = 0.0;
        private Double loadBalancerCost = 0.0;
        private Double gpuCost = 0.0;
        private Double sharedCost = 0.0;
        private Double externalCost = 0.0;

        // Efficiency metrics
        private Double cpuEfficiency = 0.0;
        private Double ramEfficiency = 0.0;
        private Double totalEfficiency = 0.0;

        // Idle and savings
        private Double idleCost = 0.0;
        private Double cpuIdleCost = 0.0;
        private Double ramIdleCost = 0.0;

        // Resource totals
        private Double totalCpuCores = 0.0;
        private Double totalRamBytes = 0.0;
        private Double totalPvBytes = 0.0;

        // Counts
        private Integer namespaceCount = 0;
        private Integer podCount = 0;
        private Integer controllerCount = 0;

        // Getters and Setters
        public Double getTotalDailyCost() { return totalDailyCost; }
        public void setTotalDailyCost(Double totalDailyCost) { this.totalDailyCost = totalDailyCost; }

        public Double getTotalMonthlyCost() { return totalMonthlyCost; }
        public void setTotalMonthlyCost(Double totalMonthlyCost) { this.totalMonthlyCost = totalMonthlyCost; }

        public Double getCpuCost() { return cpuCost; }
        public void setCpuCost(Double cpuCost) { this.cpuCost = cpuCost; }

        public Double getRamCost() { return ramCost; }
        public void setRamCost(Double ramCost) { this.ramCost = ramCost; }

        public Double getPvCost() { return pvCost; }
        public void setPvCost(Double pvCost) { this.pvCost = pvCost; }

        public Double getNetworkCost() { return networkCost; }
        public void setNetworkCost(Double networkCost) { this.networkCost = networkCost; }

        public Double getLoadBalancerCost() { return loadBalancerCost; }
        public void setLoadBalancerCost(Double loadBalancerCost) { this.loadBalancerCost = loadBalancerCost; }

        public Double getGpuCost() { return gpuCost; }
        public void setGpuCost(Double gpuCost) { this.gpuCost = gpuCost; }

        public Double getSharedCost() { return sharedCost; }
        public void setSharedCost(Double sharedCost) { this.sharedCost = sharedCost; }

        public Double getExternalCost() { return externalCost; }
        public void setExternalCost(Double externalCost) { this.externalCost = externalCost; }

        public Double getCpuEfficiency() { return cpuEfficiency; }
        public void setCpuEfficiency(Double cpuEfficiency) { this.cpuEfficiency = cpuEfficiency; }

        public Double getRamEfficiency() { return ramEfficiency; }
        public void setRamEfficiency(Double ramEfficiency) { this.ramEfficiency = ramEfficiency; }

        public Double getTotalEfficiency() { return totalEfficiency; }
        public void setTotalEfficiency(Double totalEfficiency) { this.totalEfficiency = totalEfficiency; }

        public Double getIdleCost() { return idleCost; }
        public void setIdleCost(Double idleCost) { this.idleCost = idleCost; }

        public Double getCpuIdleCost() { return cpuIdleCost; }
        public void setCpuIdleCost(Double cpuIdleCost) { this.cpuIdleCost = cpuIdleCost; }

        public Double getRamIdleCost() { return ramIdleCost; }
        public void setRamIdleCost(Double ramIdleCost) { this.ramIdleCost = ramIdleCost; }

        public Double getTotalCpuCores() { return totalCpuCores; }
        public void setTotalCpuCores(Double totalCpuCores) { this.totalCpuCores = totalCpuCores; }

        public Double getTotalRamBytes() { return totalRamBytes; }
        public void setTotalRamBytes(Double totalRamBytes) { this.totalRamBytes = totalRamBytes; }

        public Double getTotalPvBytes() { return totalPvBytes; }
        public void setTotalPvBytes(Double totalPvBytes) { this.totalPvBytes = totalPvBytes; }

        public Integer getNamespaceCount() { return namespaceCount; }
        public void setNamespaceCount(Integer namespaceCount) { this.namespaceCount = namespaceCount; }

        public Integer getPodCount() { return podCount; }
        public void setPodCount(Integer podCount) { this.podCount = podCount; }

        public Integer getControllerCount() { return controllerCount; }
        public void setControllerCount(Integer controllerCount) { this.controllerCount = controllerCount; }
    }

    // ================================
    // Namespace Allocation
    // ================================
    public static class NamespaceAllocation {
        private String namespace;
        private String cluster;

        // Cost breakdown
        private Double cpuCost = 0.0;
        private Double ramCost = 0.0;
        private Double pvCost = 0.0;
        private Double networkCost = 0.0;
        private Double loadBalancerCost = 0.0;
        private Double gpuCost = 0.0;
        private Double sharedCost = 0.0;
        private Double externalCost = 0.0;
        private Double totalCost = 0.0;

        // Resource usage
        private Double cpuCores = 0.0;
        private Double cpuCoreHours = 0.0;
        private Double cpuCoreRequestAverage = 0.0;
        private Double cpuCoreUsageAverage = 0.0;
        private Double cpuCoreLimitAverage = 0.0;

        private Double ramBytes = 0.0;
        private Double ramByteHours = 0.0;
        private Double ramByteRequestAverage = 0.0;
        private Double ramByteUsageAverage = 0.0;
        private Double ramByteLimitAverage = 0.0;

        private Double pvBytes = 0.0;
        private Double pvByteHours = 0.0;

        // Efficiency
        private Double cpuEfficiency = 0.0;
        private Double ramEfficiency = 0.0;
        private Double totalEfficiency = 0.0;

        // Idle costs
        private Double cpuIdleCost = 0.0;
        private Double ramIdleCost = 0.0;

        // Calculated fields
        private Double costPercentage = 0.0;
        private Double monthlyCost = 0.0;
        private Double minutes = 0.0;

        // Getters and Setters
        public String getNamespace() { return namespace; }
        public void setNamespace(String namespace) { this.namespace = namespace; }

        public String getCluster() { return cluster; }
        public void setCluster(String cluster) { this.cluster = cluster; }

        public Double getCpuCost() { return cpuCost; }
        public void setCpuCost(Double cpuCost) { this.cpuCost = cpuCost; }

        public Double getRamCost() { return ramCost; }
        public void setRamCost(Double ramCost) { this.ramCost = ramCost; }

        public Double getPvCost() { return pvCost; }
        public void setPvCost(Double pvCost) { this.pvCost = pvCost; }

        public Double getNetworkCost() { return networkCost; }
        public void setNetworkCost(Double networkCost) { this.networkCost = networkCost; }

        public Double getLoadBalancerCost() { return loadBalancerCost; }
        public void setLoadBalancerCost(Double loadBalancerCost) { this.loadBalancerCost = loadBalancerCost; }

        public Double getGpuCost() { return gpuCost; }
        public void setGpuCost(Double gpuCost) { this.gpuCost = gpuCost; }

        public Double getSharedCost() { return sharedCost; }
        public void setSharedCost(Double sharedCost) { this.sharedCost = sharedCost; }

        public Double getExternalCost() { return externalCost; }
        public void setExternalCost(Double externalCost) { this.externalCost = externalCost; }

        public Double getTotalCost() { return totalCost; }
        public void setTotalCost(Double totalCost) { this.totalCost = totalCost; }

        public Double getCpuCores() { return cpuCores; }
        public void setCpuCores(Double cpuCores) { this.cpuCores = cpuCores; }

        public Double getCpuCoreHours() { return cpuCoreHours; }
        public void setCpuCoreHours(Double cpuCoreHours) { this.cpuCoreHours = cpuCoreHours; }

        public Double getCpuCoreRequestAverage() { return cpuCoreRequestAverage; }
        public void setCpuCoreRequestAverage(Double cpuCoreRequestAverage) { this.cpuCoreRequestAverage = cpuCoreRequestAverage; }

        public Double getCpuCoreUsageAverage() { return cpuCoreUsageAverage; }
        public void setCpuCoreUsageAverage(Double cpuCoreUsageAverage) { this.cpuCoreUsageAverage = cpuCoreUsageAverage; }

        public Double getCpuCoreLimitAverage() { return cpuCoreLimitAverage; }
        public void setCpuCoreLimitAverage(Double cpuCoreLimitAverage) { this.cpuCoreLimitAverage = cpuCoreLimitAverage; }

        public Double getRamBytes() { return ramBytes; }
        public void setRamBytes(Double ramBytes) { this.ramBytes = ramBytes; }

        public Double getRamByteHours() { return ramByteHours; }
        public void setRamByteHours(Double ramByteHours) { this.ramByteHours = ramByteHours; }

        public Double getRamByteRequestAverage() { return ramByteRequestAverage; }
        public void setRamByteRequestAverage(Double ramByteRequestAverage) { this.ramByteRequestAverage = ramByteRequestAverage; }

        public Double getRamByteUsageAverage() { return ramByteUsageAverage; }
        public void setRamByteUsageAverage(Double ramByteUsageAverage) { this.ramByteUsageAverage = ramByteUsageAverage; }

        public Double getRamByteLimitAverage() { return ramByteLimitAverage; }
        public void setRamByteLimitAverage(Double ramByteLimitAverage) { this.ramByteLimitAverage = ramByteLimitAverage; }

        public Double getPvBytes() { return pvBytes; }
        public void setPvBytes(Double pvBytes) { this.pvBytes = pvBytes; }

        public Double getPvByteHours() { return pvByteHours; }
        public void setPvByteHours(Double pvByteHours) { this.pvByteHours = pvByteHours; }

        public Double getCpuEfficiency() { return cpuEfficiency; }
        public void setCpuEfficiency(Double cpuEfficiency) { this.cpuEfficiency = cpuEfficiency; }

        public Double getRamEfficiency() { return ramEfficiency; }
        public void setRamEfficiency(Double ramEfficiency) { this.ramEfficiency = ramEfficiency; }

        public Double getTotalEfficiency() { return totalEfficiency; }
        public void setTotalEfficiency(Double totalEfficiency) { this.totalEfficiency = totalEfficiency; }

        public Double getCpuIdleCost() { return cpuIdleCost; }
        public void setCpuIdleCost(Double cpuIdleCost) { this.cpuIdleCost = cpuIdleCost; }

        public Double getRamIdleCost() { return ramIdleCost; }
        public void setRamIdleCost(Double ramIdleCost) { this.ramIdleCost = ramIdleCost; }

        public Double getCostPercentage() { return costPercentage; }
        public void setCostPercentage(Double costPercentage) { this.costPercentage = costPercentage; }

        public Double getMonthlyCost() { return monthlyCost; }
        public void setMonthlyCost(Double monthlyCost) { this.monthlyCost = monthlyCost; }

        public Double getMinutes() { return minutes; }
        public void setMinutes(Double minutes) { this.minutes = minutes; }
    }

    // ================================
    // Pod Allocation
    // ================================
    public static class PodAllocation {
        private String pod;
        private String namespace;
        private String cluster;
        private String node;
        private String controller;
        private String controllerKind;

        // Costs
        private Double cpuCost = 0.0;
        private Double ramCost = 0.0;
        private Double pvCost = 0.0;
        private Double networkCost = 0.0;
        private Double totalCost = 0.0;

        // Resource usage
        private Double cpuCores = 0.0;
        private Double cpuCoreRequestAverage = 0.0;
        private Double cpuCoreUsageAverage = 0.0;
        private Double cpuCoreLimitAverage = 0.0;

        private Double ramBytes = 0.0;
        private Double ramByteRequestAverage = 0.0;
        private Double ramByteUsageAverage = 0.0;
        private Double ramByteLimitAverage = 0.0;

        // Efficiency
        private Double cpuEfficiency = 0.0;
        private Double ramEfficiency = 0.0;
        private Double totalEfficiency = 0.0;

        private Double minutes = 0.0;

        // Getters and Setters
        public String getPod() { return pod; }
        public void setPod(String pod) { this.pod = pod; }

        public String getNamespace() { return namespace; }
        public void setNamespace(String namespace) { this.namespace = namespace; }

        public String getCluster() { return cluster; }
        public void setCluster(String cluster) { this.cluster = cluster; }

        public String getNode() { return node; }
        public void setNode(String node) { this.node = node; }

        public String getController() { return controller; }
        public void setController(String controller) { this.controller = controller; }

        public String getControllerKind() { return controllerKind; }
        public void setControllerKind(String controllerKind) { this.controllerKind = controllerKind; }

        public Double getCpuCost() { return cpuCost; }
        public void setCpuCost(Double cpuCost) { this.cpuCost = cpuCost; }

        public Double getRamCost() { return ramCost; }
        public void setRamCost(Double ramCost) { this.ramCost = ramCost; }

        public Double getPvCost() { return pvCost; }
        public void setPvCost(Double pvCost) { this.pvCost = pvCost; }

        public Double getNetworkCost() { return networkCost; }
        public void setNetworkCost(Double networkCost) { this.networkCost = networkCost; }

        public Double getTotalCost() { return totalCost; }
        public void setTotalCost(Double totalCost) { this.totalCost = totalCost; }

        public Double getCpuCores() { return cpuCores; }
        public void setCpuCores(Double cpuCores) { this.cpuCores = cpuCores; }

        public Double getCpuCoreRequestAverage() { return cpuCoreRequestAverage; }
        public void setCpuCoreRequestAverage(Double cpuCoreRequestAverage) { this.cpuCoreRequestAverage = cpuCoreRequestAverage; }

        public Double getCpuCoreUsageAverage() { return cpuCoreUsageAverage; }
        public void setCpuCoreUsageAverage(Double cpuCoreUsageAverage) { this.cpuCoreUsageAverage = cpuCoreUsageAverage; }

        public Double getCpuCoreLimitAverage() { return cpuCoreLimitAverage; }
        public void setCpuCoreLimitAverage(Double cpuCoreLimitAverage) { this.cpuCoreLimitAverage = cpuCoreLimitAverage; }

        public Double getRamBytes() { return ramBytes; }
        public void setRamBytes(Double ramBytes) { this.ramBytes = ramBytes; }

        public Double getRamByteRequestAverage() { return ramByteRequestAverage; }
        public void setRamByteRequestAverage(Double ramByteRequestAverage) { this.ramByteRequestAverage = ramByteRequestAverage; }

        public Double getRamByteUsageAverage() { return ramByteUsageAverage; }
        public void setRamByteUsageAverage(Double ramByteUsageAverage) { this.ramByteUsageAverage = ramByteUsageAverage; }

        public Double getRamByteLimitAverage() { return ramByteLimitAverage; }
        public void setRamByteLimitAverage(Double ramByteLimitAverage) { this.ramByteLimitAverage = ramByteLimitAverage; }

        public Double getCpuEfficiency() { return cpuEfficiency; }
        public void setCpuEfficiency(Double cpuEfficiency) { this.cpuEfficiency = cpuEfficiency; }

        public Double getRamEfficiency() { return ramEfficiency; }
        public void setRamEfficiency(Double ramEfficiency) { this.ramEfficiency = ramEfficiency; }

        public Double getTotalEfficiency() { return totalEfficiency; }
        public void setTotalEfficiency(Double totalEfficiency) { this.totalEfficiency = totalEfficiency; }

        public Double getMinutes() { return minutes; }
        public void setMinutes(Double minutes) { this.minutes = minutes; }
    }

    // ================================
    // Controller (Deployment/StatefulSet/DaemonSet) Allocation
    // ================================
    public static class ControllerAllocation {
        private String controller;
        private String controllerKind;
        private String namespace;
        private String cluster;

        // Costs
        private Double cpuCost = 0.0;
        private Double ramCost = 0.0;
        private Double pvCost = 0.0;
        private Double networkCost = 0.0;
        private Double totalCost = 0.0;
        private Double monthlyCost = 0.0;

        // Resource usage
        private Double cpuCores = 0.0;
        private Double cpuCoreRequestAverage = 0.0;
        private Double cpuCoreUsageAverage = 0.0;

        private Double ramBytes = 0.0;
        private Double ramByteRequestAverage = 0.0;
        private Double ramByteUsageAverage = 0.0;

        // Efficiency
        private Double cpuEfficiency = 0.0;
        private Double ramEfficiency = 0.0;
        private Double totalEfficiency = 0.0;

        // Pod info
        private Integer podCount = 0;
        private Double costPerPod = 0.0;
        private Double minutes = 0.0;

        // Getters and Setters
        public String getController() { return controller; }
        public void setController(String controller) { this.controller = controller; }

        public String getControllerKind() { return controllerKind; }
        public void setControllerKind(String controllerKind) { this.controllerKind = controllerKind; }

        public String getNamespace() { return namespace; }
        public void setNamespace(String namespace) { this.namespace = namespace; }

        public String getCluster() { return cluster; }
        public void setCluster(String cluster) { this.cluster = cluster; }

        public Double getCpuCost() { return cpuCost; }
        public void setCpuCost(Double cpuCost) { this.cpuCost = cpuCost; }

        public Double getRamCost() { return ramCost; }
        public void setRamCost(Double ramCost) { this.ramCost = ramCost; }

        public Double getPvCost() { return pvCost; }
        public void setPvCost(Double pvCost) { this.pvCost = pvCost; }

        public Double getNetworkCost() { return networkCost; }
        public void setNetworkCost(Double networkCost) { this.networkCost = networkCost; }

        public Double getTotalCost() { return totalCost; }
        public void setTotalCost(Double totalCost) { this.totalCost = totalCost; }

        public Double getMonthlyCost() { return monthlyCost; }
        public void setMonthlyCost(Double monthlyCost) { this.monthlyCost = monthlyCost; }

        public Double getCpuCores() { return cpuCores; }
        public void setCpuCores(Double cpuCores) { this.cpuCores = cpuCores; }

        public Double getCpuCoreRequestAverage() { return cpuCoreRequestAverage; }
        public void setCpuCoreRequestAverage(Double cpuCoreRequestAverage) { this.cpuCoreRequestAverage = cpuCoreRequestAverage; }

        public Double getCpuCoreUsageAverage() { return cpuCoreUsageAverage; }
        public void setCpuCoreUsageAverage(Double cpuCoreUsageAverage) { this.cpuCoreUsageAverage = cpuCoreUsageAverage; }

        public Double getRamBytes() { return ramBytes; }
        public void setRamBytes(Double ramBytes) { this.ramBytes = ramBytes; }

        public Double getRamByteRequestAverage() { return ramByteRequestAverage; }
        public void setRamByteRequestAverage(Double ramByteRequestAverage) { this.ramByteRequestAverage = ramByteRequestAverage; }

        public Double getRamByteUsageAverage() { return ramByteUsageAverage; }
        public void setRamByteUsageAverage(Double ramByteUsageAverage) { this.ramByteUsageAverage = ramByteUsageAverage; }

        public Double getCpuEfficiency() { return cpuEfficiency; }
        public void setCpuEfficiency(Double cpuEfficiency) { this.cpuEfficiency = cpuEfficiency; }

        public Double getRamEfficiency() { return ramEfficiency; }
        public void setRamEfficiency(Double ramEfficiency) { this.ramEfficiency = ramEfficiency; }

        public Double getTotalEfficiency() { return totalEfficiency; }
        public void setTotalEfficiency(Double totalEfficiency) { this.totalEfficiency = totalEfficiency; }

        public Integer getPodCount() { return podCount; }
        public void setPodCount(Integer podCount) { this.podCount = podCount; }

        public Double getCostPerPod() { return costPerPod; }
        public void setCostPerPod(Double costPerPod) { this.costPerPod = costPerPod; }

        public Double getMinutes() { return minutes; }
        public void setMinutes(Double minutes) { this.minutes = minutes; }
    }

    // ================================
    // Node Allocation
    // ================================
    public static class NodeAllocation {
        private String node;
        private String cluster;
        private String instanceType;
        private String providerID;

        // Costs
        private Double cpuCost = 0.0;
        private Double ramCost = 0.0;
        private Double gpuCost = 0.0;
        private Double totalCost = 0.0;

        // Capacity
        private Double cpuCores = 0.0;
        private Double ramBytes = 0.0;
        private Double gpuCount = 0.0;

        // Usage
        private Double cpuCoreUsageAverage = 0.0;
        private Double ramByteUsageAverage = 0.0;

        // Efficiency
        private Double cpuEfficiency = 0.0;
        private Double ramEfficiency = 0.0;

        private Double minutes = 0.0;
        private Boolean preemptible = false;

        // Getters and Setters
        public String getNode() { return node; }
        public void setNode(String node) { this.node = node; }

        public String getCluster() { return cluster; }
        public void setCluster(String cluster) { this.cluster = cluster; }

        public String getInstanceType() { return instanceType; }
        public void setInstanceType(String instanceType) { this.instanceType = instanceType; }

        public String getProviderID() { return providerID; }
        public void setProviderID(String providerID) { this.providerID = providerID; }

        public Double getCpuCost() { return cpuCost; }
        public void setCpuCost(Double cpuCost) { this.cpuCost = cpuCost; }

        public Double getRamCost() { return ramCost; }
        public void setRamCost(Double ramCost) { this.ramCost = ramCost; }

        public Double getGpuCost() { return gpuCost; }
        public void setGpuCost(Double gpuCost) { this.gpuCost = gpuCost; }

        public Double getTotalCost() { return totalCost; }
        public void setTotalCost(Double totalCost) { this.totalCost = totalCost; }

        public Double getCpuCores() { return cpuCores; }
        public void setCpuCores(Double cpuCores) { this.cpuCores = cpuCores; }

        public Double getRamBytes() { return ramBytes; }
        public void setRamBytes(Double ramBytes) { this.ramBytes = ramBytes; }

        public Double getGpuCount() { return gpuCount; }
        public void setGpuCount(Double gpuCount) { this.gpuCount = gpuCount; }

        public Double getCpuCoreUsageAverage() { return cpuCoreUsageAverage; }
        public void setCpuCoreUsageAverage(Double cpuCoreUsageAverage) { this.cpuCoreUsageAverage = cpuCoreUsageAverage; }

        public Double getRamByteUsageAverage() { return ramByteUsageAverage; }
        public void setRamByteUsageAverage(Double ramByteUsageAverage) { this.ramByteUsageAverage = ramByteUsageAverage; }

        public Double getCpuEfficiency() { return cpuEfficiency; }
        public void setCpuEfficiency(Double cpuEfficiency) { this.cpuEfficiency = cpuEfficiency; }

        public Double getRamEfficiency() { return ramEfficiency; }
        public void setRamEfficiency(Double ramEfficiency) { this.ramEfficiency = ramEfficiency; }

        public Double getMinutes() { return minutes; }
        public void setMinutes(Double minutes) { this.minutes = minutes; }

        public Boolean getPreemptible() { return preemptible; }
        public void setPreemptible(Boolean preemptible) { this.preemptible = preemptible; }
    }

    // ================================
    // Cost Optimization Recommendation
    // ================================
    public static class CostOptimization {
        private String type; // RIGHT_SIZE, IDLE_RESOURCE, OVER_PROVISIONED, UNDERUTILIZED_NODE
        private String severity; // HIGH, MEDIUM, LOW
        private String resourceType; // NAMESPACE, WORKLOAD, POD, NODE
        private String resourceName;
        private String namespace;
        private String description;
        private String recommendation;

        // Current state
        private Double currentCpuRequest = 0.0;
        private Double currentMemoryRequest = 0.0;
        private Double currentCpuUsage = 0.0;
        private Double currentMemoryUsage = 0.0;
        private Double currentCost = 0.0;
        private Double efficiency = 0.0;

        // Recommended state
        private Double recommendedCpuRequest = 0.0;
        private Double recommendedMemoryRequest = 0.0;

        // Savings
        private Double estimatedMonthlySavings = 0.0;
        private Double savingsPercentage = 0.0;

        // Getters and Setters
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public String getSeverity() { return severity; }
        public void setSeverity(String severity) { this.severity = severity; }

        public String getResourceType() { return resourceType; }
        public void setResourceType(String resourceType) { this.resourceType = resourceType; }

        public String getResourceName() { return resourceName; }
        public void setResourceName(String resourceName) { this.resourceName = resourceName; }

        public String getNamespace() { return namespace; }
        public void setNamespace(String namespace) { this.namespace = namespace; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public String getRecommendation() { return recommendation; }
        public void setRecommendation(String recommendation) { this.recommendation = recommendation; }

        public Double getCurrentCpuRequest() { return currentCpuRequest; }
        public void setCurrentCpuRequest(Double currentCpuRequest) { this.currentCpuRequest = currentCpuRequest; }

        public Double getCurrentMemoryRequest() { return currentMemoryRequest; }
        public void setCurrentMemoryRequest(Double currentMemoryRequest) { this.currentMemoryRequest = currentMemoryRequest; }

        public Double getCurrentCpuUsage() { return currentCpuUsage; }
        public void setCurrentCpuUsage(Double currentCpuUsage) { this.currentCpuUsage = currentCpuUsage; }

        public Double getCurrentMemoryUsage() { return currentMemoryUsage; }
        public void setCurrentMemoryUsage(Double currentMemoryUsage) { this.currentMemoryUsage = currentMemoryUsage; }

        public Double getCurrentCost() { return currentCost; }
        public void setCurrentCost(Double currentCost) { this.currentCost = currentCost; }

        public Double getEfficiency() { return efficiency; }
        public void setEfficiency(Double efficiency) { this.efficiency = efficiency; }

        public Double getRecommendedCpuRequest() { return recommendedCpuRequest; }
        public void setRecommendedCpuRequest(Double recommendedCpuRequest) { this.recommendedCpuRequest = recommendedCpuRequest; }

        public Double getRecommendedMemoryRequest() { return recommendedMemoryRequest; }
        public void setRecommendedMemoryRequest(Double recommendedMemoryRequest) { this.recommendedMemoryRequest = recommendedMemoryRequest; }

        public Double getEstimatedMonthlySavings() { return estimatedMonthlySavings; }
        public void setEstimatedMonthlySavings(Double estimatedMonthlySavings) { this.estimatedMonthlySavings = estimatedMonthlySavings; }

        public Double getSavingsPercentage() { return savingsPercentage; }
        public void setSavingsPercentage(Double savingsPercentage) { this.savingsPercentage = savingsPercentage; }
    }

    // ================================
    // Main class Getters and Setters
    // ================================
    public Summary getSummary() { return summary; }
    public void setSummary(Summary summary) { this.summary = summary; }

    public List<NamespaceAllocation> getNamespaceAllocations() { return namespaceAllocations; }
    public void setNamespaceAllocations(List<NamespaceAllocation> namespaceAllocations) { this.namespaceAllocations = namespaceAllocations; }

    public List<PodAllocation> getPodAllocations() { return podAllocations; }
    public void setPodAllocations(List<PodAllocation> podAllocations) { this.podAllocations = podAllocations; }

    public List<ControllerAllocation> getControllerAllocations() { return controllerAllocations; }
    public void setControllerAllocations(List<ControllerAllocation> controllerAllocations) { this.controllerAllocations = controllerAllocations; }

    public List<NodeAllocation> getNodeAllocations() { return nodeAllocations; }
    public void setNodeAllocations(List<NodeAllocation> nodeAllocations) { this.nodeAllocations = nodeAllocations; }

    public List<CostOptimization> getOptimizations() { return optimizations; }
    public void setOptimizations(List<CostOptimization> optimizations) { this.optimizations = optimizations; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getLastUpdated() { return lastUpdated; }
    public void setLastUpdated(String lastUpdated) { this.lastUpdated = lastUpdated; }

    public String getClusterName() { return clusterName; }
    public void setClusterName(String clusterName) { this.clusterName = clusterName; }
}
