package com.xammer.cloud.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class DashboardData {
  private List<Account> availableAccounts;
  private Account selectedAccount;
  private String error; // <-- Field for error messages
  private static double potentialSavings;
  private boolean isMultiAccountView = false;
  private List<String> selectedAccountIds;
  private List<String> selectedAccountNames;
  private List<String> failedAccounts;
  private String selectedProvider; // "AWS", "GCP", or null for single account
  private String lastUpdated; // Timestamp of when data was cached/fetched

  public boolean isMultiAccountView() {
    return isMultiAccountView;
  }

  public void setMultiAccountView(boolean multiAccountView) {
    isMultiAccountView = multiAccountView;
  }

  public List<String> getSelectedAccountIds() {
    return selectedAccountIds;
  }

  public void setSelectedAccountIds(List<String> selectedAccountIds) {
    this.selectedAccountIds = selectedAccountIds;
  }

  public List<String> getSelectedAccountNames() {
    return selectedAccountNames;
  }

  public void setSelectedAccountNames(List<String> selectedAccountNames) {
    this.selectedAccountNames = selectedAccountNames;
  }

  public List<String> getFailedAccounts() {
    return failedAccounts;
  }

  public void setFailedAccounts(List<String> failedAccounts) {
    this.failedAccounts = failedAccounts;
  }

  @Data
  @AllArgsConstructor
  @NoArgsConstructor
  public static class ResourceInventory {
    private int vpc;
    private int ecs;
    private int ec2;
    private int kubernetes;
    private int lambdas;
    private int ebsVolumes;
    private int images;
    private int snapshots;
    private int s3Buckets;
    private int rdsInstances;
    private int route53Zones;
    private int loadBalancers;
    private int firewalls;
    private int cloudNatRouters;
    private int artifactRepositories;
    private int kmsKeys;
    private int cloudFunctions;
    private int cloudBuildTriggers;
    private int secretManagerSecrets;
    private int cloudArmorPolicies;
    private int lightsail;
    private int amplify;

    // Frontend compatibility fields
    private int ec2Count;
    private int s3Count;
    private int rdsCount;
    private int vpcCount;
    private int lambdaCount;
    private int ebsCount;
    private int route53Count;
    private int loadBalancerCount;
    private int ecsCount;
    private int eksCount;
    private int lightsailCount;
    private int amplifyCount;

    public long getTotalCount() {
      return (long) vpc + ecs + ec2 + kubernetes + lambdas + ebsVolumes + images + snapshots + s3Buckets
          + rdsInstances
          + route53Zones + loadBalancers + firewalls + cloudNatRouters + artifactRepositories + kmsKeys +
          cloudFunctions + cloudBuildTriggers + secretManagerSecrets + cloudArmorPolicies + lightsail
          + amplify;
    }
  }

  @Data
  @AllArgsConstructor
  @NoArgsConstructor
  public static class ServiceQuotaInfo {
    private String serviceName;
    private String quotaName;
    private double limit;
    private double usage;
    private String status;
    private String regionId;

    public ServiceQuotaInfo(String quotaName, double limit, double usage, String regionId) {
      this.serviceName = "VPC";
      this.quotaName = quotaName;
      this.limit = limit;
      this.usage = usage;
      this.status = "Active";
      this.regionId = regionId;
    }

    public ServiceQuotaInfo(
        String serviceName, String quotaName, double limit, double usage, String regionId) {
      this.serviceName = serviceName;
      this.quotaName = quotaName;
      this.limit = limit;
      this.usage = usage;
      this.status = "Active";
      this.regionId = regionId;
    }
  }

  @Data
  @AllArgsConstructor
  @NoArgsConstructor
  public static class TaggingCompliance {
    private double compliancePercentage;
    private int totalResourcesScanned;
    private int untaggedResourcesCount;
    private List<UntaggedResource> untaggedResources;
  }

  @Data
  @AllArgsConstructor
  @NoArgsConstructor
  public static class UntaggedResource {
    private String resourceId;
    private String resourceType;
    private String region;
    private List<String> missingTags;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Account {
    private String id;
    private String name;
    private List<RegionStatus> regionStatus;
    private ResourceInventory resourceInventory;
    private CloudWatchStatus cloudWatchStatus;
    private List<SecurityInsight> securityInsights;
    private CostHistory costHistory;
    private List<BillingSummary> billingSummary;
    private IamResources iamResources;
    private IamDetail iamDetails; // Added field for detailed IAM info
    private SavingsSummary savingsSummary;
    private List<OptimizationRecommendation> ec2Recommendations;
    private List<CostAnomaly> costAnomalies;
    private List<OptimizationRecommendation> ebsRecommendations;
    private List<OptimizationRecommendation> lambdaRecommendations;
    private ReservationAnalysis reservationAnalysis;
    private List<ReservationPurchaseRecommendation> reservationPurchaseRecommendations;
    private OptimizationSummary optimizationSummary;
    private List<WastedResource> wastedResources;
    private List<ServiceQuotaInfo> serviceQuotas;
    private int securityScore;
    private double monthToDateSpend;
    private double forecastedSpend;
    private double lastMonthSpend;
    private CodeQualitySummary codeQuality;
    private SystemHealthSummary systemHealth;

    // Resource Inventory counts (flattened for legacy compatibility)
    private int ec2Count;
    private int s3Count;
    private int rdsCount;
    private int vpcCount;
    private int lambdaCount;
    private int ebsCount;
    private int route53Count;
    private int loadBalancerCount;
    private int ecsCount;
    private int eksCount;
    private int lightsailCount;
    private int amplifyCount;

    // Unified Dashboard Fields
    private int totalAccounts; // <--- ADDED THIS FIELD TO FIX THE ERROR
    private int awsAccountCount;
    private int gcpAccountCount;
    private int azureAccountCount;
    private double awsCost;
    private double gcpCost;
    private double azureCost;
    private long awsResourceCount;
    private long gcpResourceCount;
    private long azureResourceCount;
    private List<String> awsAccountIds;
    private List<String> gcpProjectIds;
    private List<String> azureSubscriptionIds;

    // Per-account counts for unified dashboard breakdown modal
    private int wastedResourceCount;
    private int activeAlertCount;

    // --- NEW: Account Breakdown Lists for Smart View ---
    private List<Account> subAccounts; // Generic breakdown
    private List<Account> awsAccountList; // Specific to Unified View
    private List<Account> gcpAccountList; // Specific to Unified View
    private List<Account> azureAccountList; // Specific to Unified View

    // ✅ NEW: Constructor for lightweight breakdown objects (4 arguments)
    public Account(String id, String name, double monthToDateSpend, double forecastedSpend) {
      this.id = id;
      this.name = name;
      this.monthToDateSpend = monthToDateSpend;
      this.forecastedSpend = forecastedSpend;
    }

    // Compatibility Constructor (24 arguments)
    public Account(String id, String name, List<RegionStatus> regionStatus, ResourceInventory resourceInventory,
        CloudWatchStatus cloudWatchStatus, List<SecurityInsight> securityInsights, CostHistory costHistory,
        List<BillingSummary> billingSummary, IamResources iamResources, IamDetail iamDetails,
        SavingsSummary savingsSummary, List<OptimizationRecommendation> ec2Recommendations,
        List<CostAnomaly> costAnomalies, List<OptimizationRecommendation> ebsRecommendations,
        List<OptimizationRecommendation> lambdaRecommendations, ReservationAnalysis reservationAnalysis,
        List<ReservationPurchaseRecommendation> reservationPurchaseRecommendations,
        OptimizationSummary optimizationSummary, List<WastedResource> wastedResources,
        List<ServiceQuotaInfo> serviceQuotas, int securityScore, double monthToDateSpend,
        double forecastedSpend, double lastMonthSpend) {
      this(id, name, regionStatus, resourceInventory, cloudWatchStatus, securityInsights, costHistory,
          billingSummary, iamResources, iamDetails, savingsSummary, ec2Recommendations, costAnomalies,
          ebsRecommendations, lambdaRecommendations, reservationAnalysis, reservationPurchaseRecommendations,
          optimizationSummary, wastedResources, serviceQuotas, securityScore, monthToDateSpend,
          forecastedSpend, lastMonthSpend, null, null);
    }

    // New Constructor (26 arguments)
    public Account(String id, String name, List<RegionStatus> regionStatus, ResourceInventory resourceInventory,
        CloudWatchStatus cloudWatchStatus, List<SecurityInsight> securityInsights, CostHistory costHistory,
        List<BillingSummary> billingSummary, IamResources iamResources, IamDetail iamDetails,
        SavingsSummary savingsSummary, List<OptimizationRecommendation> ec2Recommendations,
        List<CostAnomaly> costAnomalies, List<OptimizationRecommendation> ebsRecommendations,
        List<OptimizationRecommendation> lambdaRecommendations, ReservationAnalysis reservationAnalysis,
        List<ReservationPurchaseRecommendation> reservationPurchaseRecommendations,
        OptimizationSummary optimizationSummary, List<WastedResource> wastedResources,
        List<ServiceQuotaInfo> serviceQuotas, int securityScore, double monthToDateSpend,
        double forecastedSpend, double lastMonthSpend, CodeQualitySummary codeQuality,
        SystemHealthSummary systemHealth) {
      this.id = id;
      this.name = name;
      this.regionStatus = regionStatus;
      this.resourceInventory = resourceInventory;
      this.cloudWatchStatus = cloudWatchStatus;
      this.securityInsights = securityInsights;
      this.costHistory = costHistory;
      this.billingSummary = billingSummary;
      this.iamResources = iamResources;
      this.iamDetails = iamDetails;
      this.savingsSummary = savingsSummary;
      this.ec2Recommendations = ec2Recommendations;
      this.costAnomalies = costAnomalies;
      this.ebsRecommendations = ebsRecommendations;
      this.lambdaRecommendations = lambdaRecommendations;
      this.reservationAnalysis = reservationAnalysis;
      this.reservationPurchaseRecommendations = reservationPurchaseRecommendations;
      this.optimizationSummary = optimizationSummary;
      this.wastedResources = wastedResources;
      this.serviceQuotas = serviceQuotas;
      this.securityScore = securityScore;
      this.monthToDateSpend = monthToDateSpend;
      this.forecastedSpend = forecastedSpend;
      this.lastMonthSpend = lastMonthSpend;
      this.codeQuality = codeQuality;
      this.systemHealth = systemHealth;
    }
  }

  @Data
  @NoArgsConstructor
  public static class RegionStatus {
    private String regionId;
    private String regionName;
    private String status;
    private double latitude;
    private double longitude;
    private String activeServices;

    public RegionStatus(
        String regionId,
        String regionName,
        String status,
        double latitude,
        double longitude,
        String activeServices) {
      this.regionId = regionId;
      this.regionName = regionName;
      this.status = status;
      this.latitude = latitude;
      this.longitude = longitude;
      this.activeServices = activeServices;
    }

    public RegionStatus(
        String regionId, String regionName, String status, double latitude, double longitude) {
      this(regionId, regionName, status, latitude, longitude, null);
    }

    public RegionStatus(String regionId, boolean active) {
      this.regionId = regionId;
      this.status = active ? "active" : "inactive";
    }
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class CloudWatchStatus {
    private long ok;
    private long alarm;
    private long insufficient;

    public long getAlarms() {
      return alarm;
    }

    public long getMetrics() {
      return 0;
    }

    public long getLogGroups() {
      return 0;
    }
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class IamResources {
    private int users;
    private int groups;
    private int customerManagedPolicies;
    private int roles;

    public int getPolicies() {
      return customerManagedPolicies;
    }
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class BillingSummary {
    private String serviceName;
    private double monthToDateCost;

    public double getAmount() {
      return monthToDateCost;
    }
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class SecurityInsight {
    private String title;
    private String category;
    private String severity;
    private int count;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class CodeQualitySummary {
    private String rating;
    private int bugs;
    private int vulnerabilities;
    private int codeSmells;
    private double coverage;
    private int projectCount;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class SystemHealthSummary {
    private double uptime;
    private int totalAlerts;
    private int criticalAlerts;
    private String status;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @lombok.Builder
  public static class SavingsSummary {
    // Legacy fields (for backward compatibility)
    private double realized;
    private double potential;
    private List<SavingsSuggestion> suggestions;

    // New fields for AutoSpotting integration
    private double currentMonthlyCost;
    private double onDemandMonthlyCost;
    private double actualMonthlySavings;
    private double potentialMonthlySavings;
    private int totalAsgs;
    private int enabledAsgs;

    // Constructor for just realized/potential without suggestions
    public SavingsSummary(double realized, double potential) {
      this.realized = realized;
      this.potential = potential;
      this.suggestions = java.util.Collections.emptyList();
    }

    // Constructor for potential and suggestions (realized defaults to 0.0)
    public SavingsSummary(double potential, List<SavingsSuggestion> suggestions) {
      this.realized = 0.0;
      this.potential = potential;
      this.suggestions = suggestions;
    }

    // Constructor for realized, potential, and suggestions (all three parameters)
    public SavingsSummary(double realized, double potential, List<SavingsSuggestion> suggestions) {
      this.realized = realized;
      this.potential = potential;
      this.suggestions = suggestions;
    }

    public double getTotalPotentialSavings() {
      return potential;
    }
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class SavingsSuggestion {
    private String service;
    private double suggested;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class CostHistory {
    private List<String> labels;
    private List<Double> costs;
    private List<Boolean> anomalies;

    public List<Double> getValues() {
      return costs;
    }
  }

  @Data
  @AllArgsConstructor
  @NoArgsConstructor
  public static class OptimizationRecommendation {
    private String service;
    private String resourceId;
    private String currentType;
    private String recommendedType;
    private double estimatedMonthlySavings;
    private String recommendationReason;
    private double currentMonthlyCost;
    private double recommendedMonthlyCost;
    private String region;
    private String instanceName;

    /** CPU architecture of the current resource, e.g. {@code x86_64} or {@code arm64}. */
    private String currentArchitecture;

    /** CPU architecture of the recommended target. */
    private String recommendedArchitecture;

    /**
     * True when applying this recommendation changes CPU architecture. Such a change
     * needs an AMI rebuilt for the target architecture, so it cannot be applied by a
     * stop/modify/start resize and must be surfaced as a migration.
     */
    private boolean requiresArchitectureMigration;

    public OptimizationRecommendation(
        String service,
        String resourceId,
        String currentType,
        String recommendedType,
        double estimatedMonthlySavings,
        String recommendationReason,
        double currentMonthlyCost,
        double recommendedMonthlyCost) {
      this.service = service;
      this.resourceId = resourceId;
      this.currentType = currentType;
      this.recommendedType = recommendedType;
      this.estimatedMonthlySavings = estimatedMonthlySavings;
      this.recommendationReason = recommendationReason;
      this.currentMonthlyCost = currentMonthlyCost;
      this.recommendedMonthlyCost = recommendedMonthlyCost;
    }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getInstanceName() { return instanceName; }
    public void setInstanceName(String instanceName) { this.instanceName = instanceName; }
  }

  @Data
  @AllArgsConstructor
  @NoArgsConstructor
  public static class RightsizingRecommendation {
    private String resourceId;
    private String accountName;
    private String region;
    private String service;
    private String currentType;
    private String usage;
    private String recommendedType;
    private double currentMonthlyCost;
    private double recommendedMonthlyCost;
    private double estimatedMonthlySavings;
    private List<RightsizingOption> recommendationOptions;
  }

  @Data
  @AllArgsConstructor
  @NoArgsConstructor
  public static class RightsizingOption {
    private String recommendedType;
    private double recommendedMonthlyCost;
    private double estimatedMonthlySavings;
    private String usage;
  }

  @Data
  @NoArgsConstructor
  public static class CostAnomaly {
    private String anomalyId;
    private String service;

    /** Spend above the baseline, not the total. The total on its own is not an anomaly. */
    private double unexpectedSpend;

    private LocalDate startDate;
    private LocalDate endDate;

    /**
     * What this service normally spends per day, so the anomaly can be explained rather than
     * asserted.
     *
     * <p>Every detector that produces one of these already computes a trailing average and
     * then threw it away, leaving an alert that said "unexpected spend: 4,200" with nothing to
     * judge it against. A reader cannot tell whether that is a catastrophe or a rounding error
     * without knowing the baseline it deviated from.
     *
     * <p>Zero when the detector did not supply one, which reads as "unknown" — see
     * {@link #getChangeRatio()}.
     */
    private double baselineSpend;

    /** The five-field shape, for detectors that do not report a baseline. */
    public CostAnomaly(String anomalyId, String service, double unexpectedSpend,
        LocalDate startDate, LocalDate endDate) {
      this(anomalyId, service, unexpectedSpend, startDate, endDate, 0.0);
    }

    public CostAnomaly(String anomalyId, String service, double unexpectedSpend,
        LocalDate startDate, LocalDate endDate, double baselineSpend) {
      this.anomalyId = anomalyId;
      this.service = service;
      this.unexpectedSpend = unexpectedSpend;
      this.startDate = startDate;
      this.endDate = endDate;
      this.baselineSpend = baselineSpend;
    }

    /**
     * How many times its normal spend this service reached, or 0 when unknown.
     *
     * <p>{@code 0} rather than {@code 1} for the unknown case: a caller grading severity by
     * ratio must be able to tell "no baseline" apart from "exactly normal", and 1.0 would
     * silently claim the latter.
     */
    public double getChangeRatio() {
      if (baselineSpend <= 0) {
        return 0.0;
      }
      return (baselineSpend + unexpectedSpend) / baselineSpend;
    }
  }

  @Data
  @AllArgsConstructor
  @NoArgsConstructor
  public static class ReservationAnalysis {
    private double utilizationPercentage;
    private double coveragePercentage;
  }

  @Data
  @AllArgsConstructor
  @NoArgsConstructor
  public static class ReservationPurchaseRecommendation {
    private String instanceFamily;
    private String recommendedInstances;
    private String recommendedUnits;
    private String minimumUnits;
    private String monthlySavings;
    private String onDemandCost;
    private String estimatedMonthlyCost;
    private String term;
    private String instanceType;
    private String region;
    private String platform;
    private String tenancy;
    private String generation;
    private boolean sizeFlex;
  }

  @Data
  @AllArgsConstructor
  @NoArgsConstructor
  public static class OptimizationSummary {
    private double totalPotentialSavings;
    private long criticalAlertsCount;

    public double getPotentialSavings() {
      return totalPotentialSavings;
    }

    public long getCriticalAlerts() {
      return criticalAlertsCount;
    }
  }

  @Data
  @NoArgsConstructor
  public static class WastedResource {
    private String resourceId;
    private String resourceName;
    private String resourceType;
    private String region;
    /** Estimated monthly savings in USD; valid only when {@link #savingsUnknown} is false. */
    private double monthlySavings;
    private String reason;

    /**
     * True when the resource's cost could not be determined, so {@link #monthlySavings} is
     * a placeholder zero rather than a real figure.
     *
     * <p>Needed because the pricing lookups return {@code 0.0} on failure. Without this
     * flag an idle instance whose price lookup failed appeared to cost nothing, quietly
     * understating a customer's waste in the dashboard total.
     */
    private boolean savingsUnknown;

    /**
     * True when the resource is billed at nothing, so cleaning it up frees no spend.
     *
     * <p>Unattached ENIs, unused security groups, empty log groups, idle ECS cluster
     * records and ACM public certificates are all free. They are still worth surfacing as
     * hygiene and blast-radius findings, but counting them alongside billable waste
     * produced headline figures like "257 wasted resources — $18.83/mo", where the count
     * and the amount describe almost disjoint sets of resources.
     *
     * <p>Distinct from {@link #savingsUnknown}: that means the cost is real but could not
     * be priced, this means the cost is known to be zero.
     */
    private boolean noCostImpact;

    public WastedResource(String resourceId, String resourceName, String resourceType,
                          String region, double monthlySavings, String reason) {
      this.resourceId = resourceId;
      this.resourceName = resourceName;
      this.resourceType = resourceType;
      this.region = region;
      this.monthlySavings = monthlySavings;
      this.reason = reason;
      this.savingsUnknown = false;
      this.noCostImpact = false;
    }

    /**
     * Creates a wasted resource whose cost impact is real but could not be priced.
     */
    public static WastedResource withUnknownSavings(String resourceId, String resourceName,
                                                    String resourceType, String region, String reason) {
      WastedResource r = new WastedResource(resourceId, resourceName, resourceType, region, 0.0, reason);
      r.setSavingsUnknown(true);
      return r;
    }

    /**
     * Creates a hygiene finding for a resource that is genuinely free.
     *
     * @see #noCostImpact
     */
    public static WastedResource withNoCostImpact(String resourceId, String resourceName,
                                                  String resourceType, String region, String reason) {
      WastedResource r = new WastedResource(resourceId, resourceName, resourceType, region, 0.0, reason);
      r.setNoCostImpact(true);
      return r;
    }

    /** True when this finding should contribute to a reclaimable-spend total. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isBillableWaste() {
      return !savingsUnknown && !noCostImpact;
    }
  }

  @Data
  @AllArgsConstructor
  @NoArgsConstructor
  public static class ServiceGroupDto {
    private String serviceType;
    private List<ResourceDto> resources;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class SecurityFinding {
    private String resourceId;
    private String region;
    private String category;
    private String severity;
    private String description;
    private String complianceFramework;
    private String controlId;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class BudgetDetails {
    private String budgetName;
    private BigDecimal budgetLimit;
    private String budgetUnit;
    private BigDecimal actualSpend;
    private BigDecimal forecastedSpend;
    private String notificationEmail;
    private String serviceName; // Optional: filter budget to a specific AWS service

    public BudgetDetails(
        String budgetName,
        BigDecimal budgetLimit,
        String budgetUnit,
        BigDecimal actualSpend,
        BigDecimal forecastedSpend) {
      this.budgetName = budgetName;
      this.budgetLimit = budgetLimit;
      this.budgetUnit = budgetUnit;
      this.actualSpend = actualSpend;
      this.forecastedSpend = forecastedSpend;
    }
  }

  // --- NEW IAM DETAILS CLASSES ---

  @Data
  @AllArgsConstructor
  @NoArgsConstructor
  public static class IamDetail {
    private List<IamUserDetail> users;
    private List<IamRoleDetail> roles;

    /**
     * True when policy documents could not be read, so the risk fields below are
     * "unknown" rather than "clean".
     *
     * <p>
     * The distinction matters more than it looks. An identity with no findings and an
     * identity we were never allowed to inspect both render as an unflagged card, and
     * only this flag lets the UI say which one it is showing. Set when the account's
     * role is missing {@code iam:GetPolicyVersion} / {@code iam:GetUserPolicy}.
     */
    private boolean permissionAnalysisDegraded;

    /** Why analysis was degraded, surfaced verbatim in the UI banner. */
    private String permissionAnalysisNote;

    /**
     * True when the inventory is complete but the privilege audit is still running.
     *
     * <p>
     * Distinct from {@link #permissionAnalysisDegraded}, which means the audit ran and could
     * not read everything. This means it has not finished yet, and the risk fields will be
     * populated by a background pass that rewrites this account's cache entry.
     *
     * <p>
     * The distinction exists because the audit is the expensive half of the scan — two IAM
     * calls per distinct policy, run serially — and on a large account it does not fit inside
     * the gateway's timeout. Rather than let the whole request die and show nothing, the
     * inventory is returned as soon as it is ready. Without this flag the UI could not tell
     * that apart from an account that genuinely has no risk findings, which would put a clean
     * bill of health on identities nobody had inspected yet.
     */
    private boolean permissionAnalysisPending;

    /**
     * When this scan was produced, as an ISO-8601 instant.
     *
     * <p>
     * Present because this payload is now served from a durable snapshot rather than only from
     * a live scan, and an access review is not something to show without saying how old it is.
     * "Three users hold AdministratorAccess" means something different if it was true last
     * Tuesday.
     *
     * <p>
     * An instant rather than a formatted string so the browser can render it in the viewer's
     * own timezone. Null only on payloads that never went through the cache — the GCP and Azure
     * builders, which assemble {@code IamDetail} inside their own dashboard aggregates.
     */
    private String lastUpdated;

    /**
     * True when this result was served from cache and a rescan is already running behind it.
     *
     * <p>
     * Distinct from {@link #permissionAnalysisPending}, which is about the risk scoring half of
     * one scan. This says the whole snapshot is being rebuilt, which is the normal path after
     * the durable snapshot goes stale or an IAM change event arrives: the request is answered
     * from what we have and the fresh result lands a moment later. Without the flag the UI has
     * no way to know a newer answer is coming, so it would sit on the old one until the user
     * pressed Reload.
     */
    private boolean refreshing;

    public IamDetail(List<IamUserDetail> users, List<IamRoleDetail> roles) {
      this.users = users;
      this.roles = roles;
    }
  }

  @Data
  @AllArgsConstructor
  @NoArgsConstructor
  public static class IamUserDetail {
    private String userName;
    private String userId;
    private String arn;
    private String createDate;
    private String passwordLastUsed;
    private List<String> groups;
    private List<IamPolicyDetail> attachedPolicies;
    private boolean mfaEnabled;

    // --- Permission risk, populated by IamPrivilegeAnalyzer (AWS only for now) ---

    /** "CRITICAL" | "HIGH" | "MEDIUM" | "LOW" | "NONE" — highest severity in {@link #risks}. */
    private String riskLevel;
    private int riskScore;
    private List<IamAccessRisk> risks;

    /**
     * The single flag the card canvas paints red. True when at least one HIGH or
     * CRITICAL risk was found, i.e. this identity holds more permission than it
     * should — not merely that some advisory note exists.
     */
    private boolean excessPermissions;

    /** Retains the pre-risk-analysis arity so existing AWS/GCP/Azure call sites compile. */
    public IamUserDetail(String userName, String userId, String arn, String createDate,
        String passwordLastUsed, List<String> groups, List<IamPolicyDetail> attachedPolicies,
        boolean mfaEnabled) {
      this.userName = userName;
      this.userId = userId;
      this.arn = arn;
      this.createDate = createDate;
      this.passwordLastUsed = passwordLastUsed;
      this.groups = groups;
      this.attachedPolicies = attachedPolicies;
      this.mfaEnabled = mfaEnabled;
    }
  }

  @Data
  @AllArgsConstructor
  @NoArgsConstructor
  public static class IamRoleDetail {
    private String roleName;
    private String roleId;
    private String arn;
    private String createDate;
    private String description;
    private List<IamPolicyDetail> attachedPolicies;

    // --- Permission risk, populated by IamPrivilegeAnalyzer (AWS only for now) ---

    private String riskLevel;
    private int riskScore;
    private List<IamAccessRisk> risks;
    private boolean excessPermissions;

    /** Retains the pre-risk-analysis arity so existing AWS/GCP/Azure call sites compile. */
    public IamRoleDetail(String roleName, String roleId, String arn, String createDate,
        String description, List<IamPolicyDetail> attachedPolicies) {
      this.roleName = roleName;
      this.roleId = roleId;
      this.arn = arn;
      this.createDate = createDate;
      this.description = description;
      this.attachedPolicies = attachedPolicies;
    }
  }

  @Data
  @AllArgsConstructor
  @NoArgsConstructor
  public static class IamPolicyDetail {
    private String policyName;
    private String policyArn; // Null for inline policies
    private String type; // "Managed" or "Inline"

    // --- Document analysis, populated by IamPrivilegeAnalyzer (AWS only for now) ---

    /**
     * False when the document was never fetched or the fetch was denied. Without it the
     * UI cannot tell an audited-and-clean policy from an unaudited one.
     */
    private boolean documentAnalyzed;

    /** Grants {@code Action:*} on {@code Resource:*} — effectively account administrator. */
    private boolean adminAccess;

    /** Service-wide wildcards this policy allows, e.g. {@code s3:*}, {@code kms:*}. */
    private List<String> wildcardActions;

    /** Actions that permit self-escalation, e.g. {@code iam:PutUserPolicy}. */
    private List<String> escalationActions;

    /**
     * Group this policy is inherited from, or null when attached directly to the identity.
     *
     * <p>
     * Necessary for correctness, not presentation. Most organisations grant administrator
     * access to a group rather than to a user, so a permission audit that only reads directly
     * attached policies reports the real administrators as having no excess access — the worst
     * possible false negative. It also tells the reader where to go to fix it: the grant lives
     * on the group, and detaching it from the user is not an available action.
     */
    private String inheritedFrom;

    /** Retains the 3-arg arity used across DashboardDataService and AzureDashboardService. */
    public IamPolicyDetail(String policyName, String policyArn, String type) {
      this.policyName = policyName;
      this.policyArn = policyArn;
      this.type = type;
    }

    /** Group-inherited policy. */
    public IamPolicyDetail(String policyName, String policyArn, String type, String inheritedFrom) {
      this.policyName = policyName;
      this.policyArn = policyArn;
      this.type = type;
      this.inheritedFrom = inheritedFrom;
    }
  }

  /**
   * One reason an identity is over-privileged.
   *
   * <p>
   * Deliberately shaped like {@link SecurityFinding} — code, severity, description — so the
   * Identity tab and the Findings tab of the Security Center read as the same product rather
   * than two bolted-together views, and so these can later be promoted into the findings
   * stream without a translation layer.
   */
  @Data
  @AllArgsConstructor
  @NoArgsConstructor
  public static class IamAccessRisk {
    /** Stable identifier, e.g. "IAM-EXC-001". Safe to key remediation docs off. */
    private String code;

    /** "CRITICAL" | "HIGH" | "MEDIUM" | "LOW". */
    private String severity;

    /** Short label for the red chip on the card, e.g. "Full administrator access". */
    private String title;

    /** One sentence explaining the exposure in the customer's own terms. */
    private String description;

    /** The policy the risk came from, so the card can point at something actionable. */
    private String sourcePolicy;

    /** Concrete evidence — the offending actions, capped by the analyzer. */
    private List<String> evidence;
  }

}