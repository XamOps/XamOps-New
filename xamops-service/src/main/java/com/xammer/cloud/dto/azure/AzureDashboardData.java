package com.xammer.cloud.dto.azure;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.xammer.cloud.dto.DashboardData;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * Detected cost anomalies.
     *
     * <p>
     * Typed as the shared {@link DashboardData.CostAnomaly} so an Azure anomaly carries the same
     * fields as an AWS or GCP one. This used to be a local {@code AzureDashboardData.CostAnomaly}
     * that declared <strong>no fields at all</strong> — an empty placeholder class. That is why
     * Azure could never report an anomaly regardless of detection: the payload had nowhere to put
     * one, and {@code AzureDashboardService} filled the list with {@code Collections.emptyList()},
     * which renders identically to a subscription that was checked and found clean.
     */
    private List<DashboardData.CostAnomaly> costAnomalies;

    // Cost by region map (populated from Redis cache)
    private Map<String, Double> costByRegion = new HashMap<>();

    // *** ADDED: KPI fields for dashboard display ***
    private double monthToDateSpend;
    private double forecastedSpend;
    private double lastMonthSpend;

    // Recommendations
    private List<RightsizingRecommendation> vmRecommendations;
    private List<RightsizingRecommendation> diskRecommendations;
    private List<RightsizingRecommendation> functionRecommendations;

    // Hints/IAM
    private DashboardData.IamDetail iamDetails;

    /**
     * Defender secure score as a percentage, or {@code 0} when it is not known.
     *
     * <p>
     * This field exists because the unified dashboard previously hardcoded a security score of
     * 100 for every Azure subscription — there was nothing on this DTO to map from, so
     * {@code mapAzureDataToDashboardData} invented a perfect score. Defender was computing a real
     * one the whole time; it simply never reached the dashboard.
     *
     * <p>
     * Zero means "not known", not "zero posture". Defender reports points earned against a
     * maximum it scales to the controls in scope, so this is stored as a percentage of that
     * maximum rather than raw points. It is left at zero whenever Defender was unreachable, the
     * service principal lacks the Security Reader role, or the subscription has no Defender data
     * — all cases where any number would be a guess. The dashboard already treats a falsy score
     * as "N/A" and excludes it from the cross-provider average, so zero degrades correctly
     * without a nullable type.
     */
    private int securityScore;

    /** Unhealthy Defender assessments, grouped by category and severity. */
    private List<DashboardData.SecurityInsight> securityInsights = new java.util.ArrayList<>();

    /**
     * Why {@link #securityScore} is unavailable, or null when it is available.
     *
     * <p>
     * Carried so the reason travels with the data. "Security Reader is not assigned" and "this
     * subscription has no Defender data" are different problems with different fixes, and a bare
     * absent score cannot tell them apart.
     */
    private String securityScoreUnavailableReason;

    // Cache metadata - shows when data was last refreshed
    private String lastUpdated;

    /**
     * Age of the snapshot this payload came from, in minutes, or null when it was just built live.
     *
     * <p>
     * The Azure dashboard serves a {@code cached_data} row that has no TTL, so "the numbers on
     * screen" and "the numbers in Azure" can be arbitrarily far apart with nothing saying so. The
     * frontend previously showed {@code lastUpdated} as a bare timestamp, which reads as
     * reassurance rather than as an age — a snapshot from three days ago and one from three
     * minutes ago look equally authoritative.
     *
     * <p>
     * Exposed as a number rather than a formatted string so the UI decides the threshold at which
     * it starts warning, and so it can be asserted on in a test.
     */
    private Long snapshotAgeMinutes;

    /**
     * True when the snapshot is older than the refresh cadence should allow.
     *
     * <p>
     * Set by the service rather than computed in the browser, because the cadence is a server-side
     * configuration value and the browser has no way to know it.
     */
    private boolean stale;

    public Long getSnapshotAgeMinutes() { return snapshotAgeMinutes; }
    public void setSnapshotAgeMinutes(Long snapshotAgeMinutes) { this.snapshotAgeMinutes = snapshotAgeMinutes; }

    public boolean isStale() { return stale; }
    public void setStale(boolean stale) { this.stale = stale; }

    /**
     * False when at least one section could not be populated, so the zeros in this payload are
     * placeholders rather than measurements.
     *
     * <p>Cost figures are read from a Redis cache filled by a separate billing-ingestion job.
     * When that cache is cold the dashboard builder previously set month-to-date and forecast
     * spend to {@code 0.0} and carried on, and the result was written straight over the good
     * database snapshot — so pressing Refresh replaced a populated dashboard with an
     * all-zero one that looked authoritative.
     */
    private boolean dataComplete = true;

    /** Human-readable reasons why {@link #dataComplete} is false. */
    private java.util.List<String> dataWarnings = new java.util.ArrayList<>();

    public boolean isDataComplete() { return dataComplete; }
    public void setDataComplete(boolean dataComplete) { this.dataComplete = dataComplete; }

    public java.util.List<String> getDataWarnings() { return dataWarnings; }
    public void setDataWarnings(java.util.List<String> dataWarnings) { this.dataWarnings = dataWarnings; }

    /**
     * Records a reason this payload is incomplete.
     *
     * <p>
     * Synchronized because {@code AzureDashboardService} builds the payload from six parallel futures
     * and several of them can add a warning. Concurrent {@code ArrayList.add} can drop an element or
     * leave a null hole, and the frontend banner is gated on {@code dataWarnings.length} — so a lost
     * warning silences the explanation for a zero that is still on screen, which is the exact failure
     * this field exists to prevent.
     */
    public synchronized void addDataWarning(String warning) {
        this.dataComplete = false;
        if (this.dataWarnings == null) {
            this.dataWarnings = new java.util.ArrayList<>();
        }
        this.dataWarnings.add(warning);
    }

    /**
     * True when this payload carries no spend and no inventory, i.e. it conveys nothing.
     *
     * <p>Used to refuse overwriting a populated snapshot with an empty one.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isMateriallyEmpty() {
        boolean noSpend = monthToDateSpend == 0.0 && forecastedSpend == 0.0 && lastMonthSpend == 0.0;
        boolean noHistory = costHistory == null || costHistory.getCosts() == null
                || costHistory.getCosts().isEmpty();
        boolean noBilling = billingSummary == null || billingSummary.isEmpty();
        return noSpend && noHistory && noBilling;
    }

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

    public List<DashboardData.CostAnomaly> getCostAnomalies() {
        return costAnomalies;
    }

    public void setCostAnomalies(List<DashboardData.CostAnomaly> costAnomalies) {
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

    public double getLastMonthSpend() {
        return lastMonthSpend;
    }

    public void setLastMonthSpend(double lastMonthSpend) {
        this.lastMonthSpend = lastMonthSpend;
    }

    public Map<String, Double> getCostByRegion() {
        return costByRegion;
    }

    public void setCostByRegion(Map<String, Double> costByRegion) {
        this.costByRegion = costByRegion != null ? costByRegion : new HashMap<>();
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

    public DashboardData.IamDetail getIamDetails() {
        return iamDetails;
    }

    public void setIamDetails(DashboardData.IamDetail iamDetails) {
        this.iamDetails = iamDetails;
    }

    public int getSecurityScore() {
        return securityScore;
    }

    public void setSecurityScore(int securityScore) {
        this.securityScore = securityScore;
    }

    public List<DashboardData.SecurityInsight> getSecurityInsights() {
        return securityInsights;
    }

    public void setSecurityInsights(List<DashboardData.SecurityInsight> securityInsights) {
        this.securityInsights = securityInsights != null ? securityInsights : new java.util.ArrayList<>();
    }

    public String getSecurityScoreUnavailableReason() {
        return securityScoreUnavailableReason;
    }

    public void setSecurityScoreUnavailableReason(String securityScoreUnavailableReason) {
        this.securityScoreUnavailableReason = securityScoreUnavailableReason;
    }

    public String getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(String lastUpdated) {
        this.lastUpdated = lastUpdated;
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

        public long getVirtualMachines() {
            return virtualMachines;
        }

        public void setVirtualMachines(long virtualMachines) {
            this.virtualMachines = virtualMachines;
        }

        public long getStorageAccounts() {
            return storageAccounts;
        }

        public void setStorageAccounts(long storageAccounts) {
            this.storageAccounts = storageAccounts;
        }

        public long getSqlDatabases() {
            return sqlDatabases;
        }

        public void setSqlDatabases(long sqlDatabases) {
            this.sqlDatabases = sqlDatabases;
        }

        public long getVirtualNetworks() {
            return virtualNetworks;
        }

        public void setVirtualNetworks(long virtualNetworks) {
            this.virtualNetworks = virtualNetworks;
        }

        public long getFunctions() {
            return functions;
        }

        public void setFunctions(long functions) {
            this.functions = functions;
        }

        public long getDisks() {
            return disks;
        }

        public void setDisks(long disks) {
            this.disks = disks;
        }

        public long getDnsZones() {
            return dnsZones;
        }

        public void setDnsZones(long dnsZones) {
            this.dnsZones = dnsZones;
        }

        public long getLoadBalancers() {
            return loadBalancers;
        }

        public void setLoadBalancers(long loadBalancers) {
            this.loadBalancers = loadBalancers;
        }

        public long getContainerInstances() {
            return containerInstances;
        }

        public void setContainerInstances(long containerInstances) {
            this.containerInstances = containerInstances;
        }

        public long getKubernetesServices() {
            return kubernetesServices;
        }

        public void setKubernetesServices(long kubernetesServices) {
            this.kubernetesServices = kubernetesServices;
        }

        public long getAppServices() {
            return appServices;
        }

        public void setAppServices(long appServices) {
            this.appServices = appServices;
        }

        public long getStaticWebApps() {
            return staticWebApps;
        }

        public void setStaticWebApps(long staticWebApps) {
            this.staticWebApps = staticWebApps;
        }
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

        public String getService() {
            return service;
        }

        public void setService(String service) {
            this.service = service;
        }

        public double getCost() {
            return cost;
        }

        public void setCost(double cost) {
            this.cost = cost;
        }
    }

    /**
     * Holds data for the historical cost chart.
     */
    public static class CostHistory {
        private List<String> labels;
        private List<Double> costs;

        /**
         * Per-point flag marking a label as a <strong>forecast</strong> rather than actual spend.
         *
         * <p>
         * This was named {@code anomalies}, which it never was. The ingestion job sets it true for
         * the {@code "MMM yyyy (Forecast)"} point it projects, and every reader treats it that way:
         * {@code AzureFinOpsService} sums the costs where it is true to produce forecast spend, and
         * filters them out to compute month-over-month change. Nothing anywhere read it as an
         * anomaly signal — real anomalies live in {@link AzureDashboardData#getCostAnomalies()} — but
         * the name meant a reader could reasonably conclude Azure had anomaly detection when it had
         * none.
         *
         * <p>
         * {@code getAnomalies()}/{@code setAnomalies()} remain as deprecated delegates onto this same
         * list so existing callers keep working, and {@code @JsonAlias} lets already-cached snapshots
         * written under the old key deserialize without a forced re-ingestion.
         */
        private List<Boolean> forecast;

        public List<String> getLabels() {
            return labels;
        }

        public void setLabels(List<String> labels) {
            this.labels = labels;
        }

        public List<Double> getCosts() {
            return costs;
        }

        public void setCosts(List<Double> costs) {
            this.costs = costs;
        }

        @com.fasterxml.jackson.annotation.JsonProperty("forecast")
        public List<Boolean> getForecast() {
            return forecast;
        }

        @com.fasterxml.jackson.annotation.JsonProperty("forecast")
        @com.fasterxml.jackson.annotation.JsonAlias("anomalies")
        public void setForecast(List<Boolean> forecast) {
            this.forecast = forecast;
        }

        /**
         * @deprecated misnamed; this list marks forecast points, not anomalies.
         *             Use {@link #getForecast()}. Real cost anomalies are on
         *             {@link AzureDashboardData#getCostAnomalies()}.
         */
        @Deprecated
        @com.fasterxml.jackson.annotation.JsonIgnore
        public List<Boolean> getAnomalies() {
            return forecast;
        }

        /**
         * @deprecated misnamed; use {@link #setForecast(List)}.
         */
        @Deprecated
        @com.fasterxml.jackson.annotation.JsonIgnore
        public void setAnomalies(List<Boolean> anomalies) {
            this.forecast = anomalies;
        }
    }

    /**
     * Represents the status of a single Azure region.
     */
    public static class RegionStatus {
        private String name;
        private double latitude;
        private double longitude;
        private String status;

        public RegionStatus() {
        }

        public RegionStatus(String name, double latitude, double longitude, String status) {
            this.name = name;
            this.latitude = latitude;
            this.longitude = longitude;
            this.status = status;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public double getLatitude() {
            return latitude;
        }

        public void setLatitude(double latitude) {
            this.latitude = latitude;
        }

        public double getLongitude() {
            return longitude;
        }

        public void setLongitude(double longitude) {
            this.longitude = longitude;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }
    }

    /**
     * Represents a single rightsizing recommendation.
     */
    public static class RightsizingRecommendation {
    }
}
