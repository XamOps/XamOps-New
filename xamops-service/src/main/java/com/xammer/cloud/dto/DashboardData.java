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
  private String error; // <-- CRITICAL FIX: This field was missing
  private static double potentialSavings;
  private boolean isMultiAccountView = false;
  private List<String> selectedAccountIds;
  private List<String> selectedAccountNames;
  private List<String> failedAccounts;

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
    private String description;
    private String category;
    private int count;
  }

  // In xamops-service/src/main/java/com/xammer/cloud/dto/DashboardData.java

  // In xamops-service/src/main/java/com/xammer/cloud/dto/DashboardData.java

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

    public OptimizationRecommendation(
        String service,
        String resourceId,
        String currentType,
        String recommendedType,
        double estimatedMonthlySavings,
        String recommendationReason,
        double currentMonthlyCost) {
      this.service = service;
      this.resourceId = resourceId;
      this.currentType = currentType;
      this.recommendedType = recommendedType;
      this.estimatedMonthlySavings = estimatedMonthlySavings;
      this.recommendationReason = recommendationReason;
      this.currentMonthlyCost = currentMonthlyCost;
    }
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
  @AllArgsConstructor
  @NoArgsConstructor
  public static class CostAnomaly {
    private String anomalyId;
    private String service;
    private double unexpectedSpend;
    private LocalDate startDate;
    private LocalDate endDate;
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
  @AllArgsConstructor
  @NoArgsConstructor
  public static class WastedResource {
    private String resourceId;
    private String resourceName;
    private String resourceType;
    private String region;
    private double monthlySavings;
    private String reason;
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
    private String notificationEmail; // Add this line

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
  }

  @Data
  @AllArgsConstructor
  @NoArgsConstructor
  public static class IamPolicyDetail {
    private String policyName;
    private String policyArn; // Null for inline policies
    private String type; // "Managed" or "Inline"
  }

}