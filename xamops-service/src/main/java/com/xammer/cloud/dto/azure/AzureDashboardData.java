package com.xammer.cloud.dto.azure;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.xammer.cloud.dto.DashboardData;
import java.util.List;

/**
 * Main DTO for the Azure Dashboard.
 * This class holds all the data required to populate the Azure dashboard.
 */
public class AzureDashboardData {

    // Main Components
    private ResourceInventory resourceInventory;
    private List<BillingSummary> billingSummary;
    private CostHistory costHistory;
    private DashboardData.OptimizationSummary optimizationSummary;
    private List<RegionStatus> regionStatus;
    private List<CostAnomaly> costAnomalies;

    // *** ADDED: KPI fields for dashboard display ***
    private double monthToDateSpend;
    private double forecastedSpend;

    // Recommendations
    private List<RightsizingRecommendation> vmRecommendations;
    private List<RightsizingRecommendation> diskRecommendations;
    private List<RightsizingRecommendation> functionRecommendations;

    // --- Getters and Setters ---

    public ResourceInventory getResourceInventory() {
        return resourceInventory;
    }

    public void setResourceInventory(ResourceInventory resourceInventory) {
        this.resourceInventory = resourceInventory;
    }

    public List<BillingSummary> getBillingSummary() {
        return billingSummary;
    }

    public void setBillingSummary(List<BillingSummary> billingSummary) {
        this.billingSummary = billingSummary;
    }

    public CostHistory getCostHistory() {
        return costHistory;
    }

    public void setCostHistory(CostHistory costHistory) {
        this.costHistory = costHistory;
    }

    public DashboardData.OptimizationSummary getOptimizationSummary() {
        return optimizationSummary;
    }

    public void setOptimizationSummary(DashboardData.OptimizationSummary optimizationSummary) {
        this.optimizationSummary = optimizationSummary;
    }

    public List<RegionStatus> getRegionStatus() {
        return regionStatus;
    }

    public void setRegionStatus(List<RegionStatus> regionStatus) {
        this.regionStatus = regionStatus;
    }

    public List<CostAnomaly> getCostAnomalies() {
        return costAnomalies;
    }

    public void setCostAnomalies(List<CostAnomaly> costAnomalies) {
        this.costAnomalies = costAnomalies;
    }

    // *** ADDED: KPI getters and setters ***
    public double getMonthToDateSpend() {
        return monthToDateSpend;
    }

    public void setMonthToDateSpend(double monthToDateSpend) {
        this.monthToDateSpend = monthToDateSpend;
    }

    public double getForecastedSpend() {
        return forecastedSpend;
    }

    public void setForecastedSpend(double forecastedSpend) {
        this.forecastedSpend = forecastedSpend;
    }

    public List<RightsizingRecommendation> getVmRecommendations() {
        return vmRecommendations;
    }

    public void setVmRecommendations(List<RightsizingRecommendation> vmRecommendations) {
        this.vmRecommendations = vmRecommendations;
    }

    public List<RightsizingRecommendation> getDiskRecommendations() {
        return diskRecommendations;
    }

    public void setDiskRecommendations(List<RightsizingRecommendation> diskRecommendations) {
        this.diskRecommendations = diskRecommendations;
    }

    public List<RightsizingRecommendation> getFunctionRecommendations() {
        return functionRecommendations;
    }

    public void setFunctionRecommendations(List<RightsizingRecommendation> functionRecommendations) {
        this.functionRecommendations = functionRecommendations;
    }

    // --- Inner Classes for Data Structure ---

    /**
     * Holds the count of various resources.
     */
    public static class ResourceInventory {
        private long virtualMachines;
        private long storageAccounts;
        private long sqlDatabases;
        private long virtualNetworks;
        private long functions;
        private long disks;
        private long dnsZones;
        private long loadBalancers;
        private long containerInstances;
        private long kubernetesServices;
        private long appServices;
        private long staticWebApps;

        public long getVirtualMachines() { return virtualMachines; }
        public void setVirtualMachines(long virtualMachines) { this.virtualMachines = virtualMachines; }
        public long getStorageAccounts() { return storageAccounts; }
        public void setStorageAccounts(long storageAccounts) { this.storageAccounts = storageAccounts; }
        public long getSqlDatabases() { return sqlDatabases; }
        public void setSqlDatabases(long sqlDatabases) { this.sqlDatabases = sqlDatabases; }
        public long getVirtualNetworks() { return virtualNetworks; }
        public void setVirtualNetworks(long virtualNetworks) { this.virtualNetworks = virtualNetworks; }
        public long getFunctions() { return functions; }
        public void setFunctions(long functions) { this.functions = functions; }
        public long getDisks() { return disks; }
        public void setDisks(long disks) { this.disks = disks; }
        public long getDnsZones() { return dnsZones; }
        public void setDnsZones(long dnsZones) { this.dnsZones = dnsZones; }
        public long getLoadBalancers() { return loadBalancers; }
        public void setLoadBalancers(long loadBalancers) { this.loadBalancers = loadBalancers; }
        public long getContainerInstances() { return containerInstances; }
        public void setContainerInstances(long containerInstances) { this.containerInstances = containerInstances; }
        public long getKubernetesServices() { return kubernetesServices; }
        public void setKubernetesServices(long kubernetesServices) { this.kubernetesServices = kubernetesServices; }
        public long getAppServices() { return appServices; }
        public void setAppServices(long appServices) { this.appServices = appServices; }
        public long getStaticWebApps() { return staticWebApps; }
        public void setStaticWebApps(long staticWebApps) { this.staticWebApps = staticWebApps; }
    }

    /**
     * Represents the cost of a single service for the billing period.
     */
    public static class BillingSummary {
        @JsonProperty("serviceName")
        private String service;
        @JsonProperty("monthToDateCost")
        private double cost;

        public BillingSummary() {
        }

        public BillingSummary(String service, double cost) {
            this.service = service;
            this.cost = cost;
        }

        public String getService() { return service; }
        public void setService(String service) { this.service = service; }
        public double getCost() { return cost; }
        public void setCost(double cost) { this.cost = cost; }
    }

    /**
     * Holds data for the historical cost chart.
     */
    public static class CostHistory {
        private List<String> labels;
        private List<Double> costs;
        private List<Boolean> anomalies;

        public List<String> getLabels() { return labels; }
        public void setLabels(List<String> labels) { this.labels = labels; }
        public List<Double> getCosts() { return costs; }
        public void setCosts(List<Double> costs) { this.costs = costs; }
        public List<Boolean> getAnomalies() { return anomalies; }
        public void setAnomalies(List<Boolean> anomalies) { this.anomalies = anomalies; }
    }

    /**
     * Represents the status of a single Azure region.
     */
    public static class RegionStatus {
        private String name;
        private double latitude;
        private double longitude;
        private String status;

        public RegionStatus() {}

        public RegionStatus(String name, double latitude, double longitude, String status) {
            this.name = name;
            this.latitude = latitude;
            this.longitude = longitude;
            this.status = status;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public double getLatitude() { return latitude; }
        public void setLatitude(double latitude) { this.latitude = latitude; }
        public double getLongitude() { return longitude; }
        public void setLongitude(double longitude) { this.longitude = longitude; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }

    /**
     * Represents a single cost anomaly.
     */
    public static class CostAnomaly {
    }

    /**
     * Represents a single rightsizing recommendation.
     */
    public static class RightsizingRecommendation {
    }
}
