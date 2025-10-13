package com.xammer.cloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.gcp.GcpDashboardData;
import com.xammer.cloud.dto.ReservationInventoryDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.gcp.GcpDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.PolicyScopeType;
import software.amazon.awssdk.services.servicequotas.ServiceQuotasClient;
import software.amazon.awssdk.services.servicequotas.model.ListServiceQuotasRequest;
import software.amazon.awssdk.services.servicequotas.model.ListServiceQuotasResponse;
import software.amazon.awssdk.services.servicequotas.model.ServiceQuota;

import java.io.IOException;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class DashboardDataService {

    private static final Logger logger = LoggerFactory.getLogger(DashboardDataService.class);

    private final CloudAccountRepository cloudAccountRepository;
    private final AwsClientProvider awsClientProvider;
    private final GcpDataService gcpDataService;
    private final CloudListService cloudListService;
    private final OptimizationService optimizationService;
    private final SecurityService securityService;
    private final FinOpsService finOpsService;
    private final ReservationService reservationService;
    private final RedisCacheService redisCache;
    private final ObjectMapper objectMapper;

    @Autowired
    public DashboardDataService(
            CloudAccountRepository cloudAccountRepository,
            AwsClientProvider awsClientProvider,
            GcpDataService gcpDataService,
            @Lazy CloudListService cloudListService,
            @Lazy OptimizationService optimizationService,
            @Lazy SecurityService securityService,
            @Lazy FinOpsService finOpsService,
            @Lazy ReservationService reservationService,
            RedisCacheService redisCache,
            ObjectMapper objectMapper) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.awsClientProvider = awsClientProvider;
        this.gcpDataService = gcpDataService;
        this.cloudListService = cloudListService;
        this.optimizationService = optimizationService;
        this.securityService = securityService;
        this.finOpsService = finOpsService;
        this.reservationService = reservationService;
        this.redisCache = redisCache;
        this.objectMapper = objectMapper;
    }


    private CloudAccount getAccount(String accountId) {
        if (!StringUtils.hasText(accountId)) {
            throw new IllegalArgumentException("Account ID cannot be null or empty.");
        }

        // Try finding by Azure Subscription ID first
        Optional<CloudAccount> azureAccount = cloudAccountRepository.findByAzureSubscriptionId(accountId);
        if (azureAccount.isPresent()) {
            return azureAccount.get();
        }

        // MODIFIED: Handle multiple accounts returned for the same ID
        List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountIdOrGcpProjectId(accountId, accountId);
        if (accounts.isEmpty()) {
            throw new RuntimeException("Account not found in database: " + accountId);
        }
        // Return the first account found to resolve the ambiguity
        return accounts.get(0);
    }


    /**
 * NEW: Fetches and aggregates dashboard data for multiple AWS accounts.
 * This method fetches data from each account in parallel and aggregates the results.
 */
public DashboardData getMultiAccountDashboardData(List<String> accountIds, boolean forceRefresh, ClientUserDetails userDetails) throws ExecutionException, InterruptedException, IOException {
    logger.info("Starting multi-account dashboard data fetch for {} accounts", accountIds.size());
    
    // Validate and filter AWS accounts only
    List<CloudAccount> validAccounts = new ArrayList<>();
    List<String> accountNames = new ArrayList<>();
    List<String> failedAccounts = new ArrayList<>();
    
    for (String accountId : accountIds) {
        try {
            CloudAccount account = getAccount(accountId);
            if ("AWS".equals(account.getProvider())) {
                validAccounts.add(account);
                accountNames.add(account.getAccountName());
            } else {
                logger.warn("Skipping non-AWS account {} in multi-account mode", accountId);
            }
        } catch (Exception e) {
            logger.error("Failed to load account {}", accountId, e);
            failedAccounts.add(accountId);
        }
    }
    
    if (validAccounts.isEmpty()) {
        throw new RuntimeException("No valid AWS accounts found for multi-account dashboard");
    }
    
    // Fetch data from all accounts in parallel
    List<CompletableFuture<DashboardData>> futures = validAccounts.stream()
            .map(account -> CompletableFuture.supplyAsync(() -> {
                try {
                    return getDashboardData(account.getAwsAccountId(), forceRefresh, userDetails);
                } catch (Exception e) {
                    logger.error("Failed to fetch data for account {}", account.getAwsAccountId(), e);
                    failedAccounts.add(account.getAwsAccountId());
                    return null;
                }
            }))
            .collect(Collectors.toList());
    
    // Wait for all futures to complete
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    
    // Collect results and filter out null values
    List<DashboardData> accountDataList = futures.stream()
            .map(CompletableFuture::join)
            .filter(data -> data != null)
            .collect(Collectors.toList());
    
    if (accountDataList.isEmpty()) {
        throw new RuntimeException("Failed to fetch data from all selected accounts");
    }
    
    // Aggregate the data
    DashboardData aggregatedData = aggregateDashboardData(accountDataList, validAccounts, accountNames, failedAccounts);
    
    // Set available accounts for dropdown
    if (userDetails != null) {
        boolean isAdmin = userDetails.getAuthorities().stream()
                .anyMatch(role -> "ROLE_BILLOPS_ADMIN".equals(role.getAuthority()));
        List<CloudAccount> userAccounts;
        if (isAdmin) {
            userAccounts = cloudAccountRepository.findAll();
        } else {
            userAccounts = cloudAccountRepository.findByClientId(userDetails.getClientId());
        }
        
        List<DashboardData.Account> availableAccounts = userAccounts.stream()
                .map(acc -> new DashboardData.Account(
                        "AWS".equals(acc.getProvider()) ? acc.getAwsAccountId() : acc.getGcpProjectId(),
                        acc.getAccountName(),
                        Collections.emptyList(),
                        null, null, Collections.emptyList(), null, Collections.emptyList(), null, null,
                        Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                        null, Collections.emptyList(), null, Collections.emptyList(), Collections.emptyList(),
                        100, 0.0, 0.0, 0.0
                ))
                .collect(Collectors.toList());
        aggregatedData.setAvailableAccounts(availableAccounts);
    }
    
    logger.info("Multi-account dashboard data aggregation complete");
    return aggregatedData;
}

/**
 * Aggregates dashboard data from multiple accounts into a single consolidated view.
 */
/**
 * Aggregates dashboard data from multiple accounts into a single consolidated view.
 */
/**
 * Aggregates dashboard data from multiple accounts into a single consolidated view.
 * Handles chronological sorting of cost history with flexible month abbreviation parsing.
 */
private DashboardData aggregateDashboardData(List<DashboardData> accountDataList, 
                                             List<CloudAccount> accounts, 
                                             List<String> accountNames,
                                             List<String> failedAccounts) {
    DashboardData aggregated = new DashboardData();
    aggregated.setMultiAccountView(true);
    aggregated.setSelectedAccountIds(accounts.stream().map(CloudAccount::getAwsAccountId).collect(Collectors.toList()));
    aggregated.setSelectedAccountNames(accountNames);
    aggregated.setFailedAccounts(failedAccounts);
    
    // Aggregate numeric metrics
    double totalMTD = 0.0;
    double totalForecast = 0.0;
    double totalLastMonth = 0.0;
    double totalPotentialSavings = 0.0;
    
    // Aggregate resource inventory
    DashboardData.ResourceInventory aggregatedInventory = new DashboardData.ResourceInventory();
    
    // Aggregate IAM resources
    int totalUsers = 0, totalGroups = 0, totalPolicies = 0, totalRoles = 0;
    
    // Aggregate CloudWatch status
    int totalAlarms = 0, totalMetrics = 0, totalLogs = 0;
    
    // Aggregate active regions (unique)
    Set<String> uniqueRegions = new HashSet<>();
    
    // Aggregate cost history (use LinkedHashMap to maintain insertion order)
    Map<String, Double> costHistoryMap = new LinkedHashMap<>();
    
    // Aggregate billing summary (map by service)
    Map<String, Double> billingSummaryMap = new HashMap<>();
    
    // Aggregate optimization summary
    double totalOptimizationSavings = 0.0;
    long totalCriticalAlerts = 0;
    
    // Process each account's data
    for (DashboardData data : accountDataList) {
        DashboardData.Account account = data.getSelectedAccount();
        if (account == null) continue;
        
        // Aggregate spend metrics
        totalMTD += account.getMonthToDateSpend();
        totalForecast += account.getForecastedSpend();
        totalLastMonth += account.getLastMonthSpend();
        
        // Aggregate savings
        if (account.getSavingsSummary() != null) {
            totalPotentialSavings += account.getSavingsSummary().getTotalPotentialSavings();
        }
        
        // Aggregate resource inventory
        if (account.getResourceInventory() != null) {
            DashboardData.ResourceInventory inv = account.getResourceInventory();
            aggregatedInventory.setVpc(aggregatedInventory.getVpc() + inv.getVpc());
            aggregatedInventory.setEc2(aggregatedInventory.getEc2() + inv.getEc2());
            aggregatedInventory.setEcs(aggregatedInventory.getEcs() + inv.getEcs());
            aggregatedInventory.setKubernetes(aggregatedInventory.getKubernetes() + inv.getKubernetes());
            aggregatedInventory.setLambdas(aggregatedInventory.getLambdas() + inv.getLambdas());
            aggregatedInventory.setEbsVolumes(aggregatedInventory.getEbsVolumes() + inv.getEbsVolumes());
            aggregatedInventory.setImages(aggregatedInventory.getImages() + inv.getImages());
            aggregatedInventory.setSnapshots(aggregatedInventory.getSnapshots() + inv.getSnapshots());
            aggregatedInventory.setS3Buckets(aggregatedInventory.getS3Buckets() + inv.getS3Buckets());
            aggregatedInventory.setRdsInstances(aggregatedInventory.getRdsInstances() + inv.getRdsInstances());
            aggregatedInventory.setRoute53Zones(aggregatedInventory.getRoute53Zones() + inv.getRoute53Zones());
            aggregatedInventory.setLoadBalancers(aggregatedInventory.getLoadBalancers() + inv.getLoadBalancers());
            aggregatedInventory.setLightsail(aggregatedInventory.getLightsail() + inv.getLightsail());
            aggregatedInventory.setAmplify(aggregatedInventory.getAmplify() + inv.getAmplify());
        }
        
        // Aggregate IAM resources
        if (account.getIamResources() != null) {
            totalUsers += account.getIamResources().getUsers();
            totalGroups += account.getIamResources().getGroups();
            totalPolicies += account.getIamResources().getPolicies();
            totalRoles += account.getIamResources().getRoles();
        }
        
        // Aggregate CloudWatch
        if (account.getCloudWatchStatus() != null) {
            totalAlarms += account.getCloudWatchStatus().getAlarms();
            totalMetrics += account.getCloudWatchStatus().getMetrics();
            totalLogs += account.getCloudWatchStatus().getLogGroups();
        }
        
        // Aggregate active regions
        if (account.getRegionStatus() != null) {
            account.getRegionStatus().forEach(region -> uniqueRegions.add(region.getRegionId()));
        }
        
        // Aggregate cost history
        if (account.getCostHistory() != null && account.getCostHistory().getLabels() != null) {
            List<String> labels = account.getCostHistory().getLabels();
            List<Double> values = account.getCostHistory().getValues() != null ? 
                                   account.getCostHistory().getValues() : 
                                   account.getCostHistory().getCosts();
            
            for (int i = 0; i < labels.size() && i < values.size(); i++) {
                String month = labels.get(i);
                double amount = values.get(i);
                costHistoryMap.put(month, costHistoryMap.getOrDefault(month, 0.0) + amount);
            }
        }
        
        // Aggregate billing summary
        if (account.getBillingSummary() != null) {
            for (DashboardData.BillingSummary billing : account.getBillingSummary()) {
                String service = billing.getServiceName();
                double amount = billing.getAmount() != 0 ? billing.getAmount() : billing.getMonthToDateCost();
                billingSummaryMap.put(service, billingSummaryMap.getOrDefault(service, 0.0) + amount);
            }
        }
        
        // Aggregate optimization summary
        if (account.getOptimizationSummary() != null) {
            totalOptimizationSavings += account.getOptimizationSummary().getTotalPotentialSavings();
            totalCriticalAlerts += account.getOptimizationSummary().getCriticalAlerts();
        }
    }
    
    // Create aggregated account object
    DashboardData.Account aggregatedAccount = new DashboardData.Account();
    aggregatedAccount.setId("multi-account");
    aggregatedAccount.setName(String.format("%d AWS Accounts", accounts.size()));
    aggregatedAccount.setMonthToDateSpend(totalMTD);
    aggregatedAccount.setForecastedSpend(totalForecast);
    aggregatedAccount.setLastMonthSpend(totalLastMonth);
    aggregatedAccount.setResourceInventory(aggregatedInventory);
    
    // Set IAM resources
    aggregatedAccount.setIamResources(new DashboardData.IamResources(totalUsers, totalGroups, totalPolicies, totalRoles));
    
    // Set CloudWatch status
    aggregatedAccount.setCloudWatchStatus(new DashboardData.CloudWatchStatus(totalAlarms, totalMetrics, totalLogs));
    
    // Set active regions
    List<DashboardData.RegionStatus> regionStatusList = uniqueRegions.stream()
            .map(regionId -> new DashboardData.RegionStatus(regionId, true))
            .collect(Collectors.toList());
    aggregatedAccount.setRegionStatus(regionStatusList);
    
    // FIXED: Set cost history with proper chronological sorting (handles "Sept" vs "Sep")
    List<String> costLabels = new ArrayList<>(costHistoryMap.keySet());
    
    // Sort labels chronologically with flexible month abbreviation handling
    costLabels.sort((a, b) -> {
        try {
            // Normalize month abbreviations: "Sept" -> "Sep", "June" -> "Jun", etc.
            String normalizedA = normalizeMonthString(a);
            String normalizedB = normalizeMonthString(b);
            
            DateTimeFormatter formatter;
            // Handle both "MMM yyyy" format (e.g., "Aug 2025") and "yyyy-MM" format
            if (normalizedA.contains(" ")) {
                formatter = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);
            } else if (normalizedA.contains("-")) {
                formatter = DateTimeFormatter.ofPattern("yyyy-MM");
            } else {
                // No recognizable format, fall back to string comparison
                return a.compareTo(b);
            }
            
            YearMonth dateA = YearMonth.parse(normalizedA, formatter);
            YearMonth dateB = YearMonth.parse(normalizedB, formatter);
            return dateA.compareTo(dateB);
        } catch (Exception e) {
            // Log warning and use fallback comparison
            logger.debug("Could not parse month labels '{}' and '{}' as dates, using string comparison", a, b);
            return normalizeMonthString(a).compareTo(normalizeMonthString(b));
        }
    });
    
    // Build sorted values and anomalies lists
    List<Double> costValues = costLabels.stream()
            .map(costHistoryMap::get)
            .collect(Collectors.toList());
    
    List<Boolean> costAnomalies = costLabels.stream()
            .map(label -> false)
            .collect(Collectors.toList());
    
    aggregatedAccount.setCostHistory(new DashboardData.CostHistory(costLabels, costValues, costAnomalies));
    
    // Set billing summary (sorted by amount descending)
    List<DashboardData.BillingSummary> billingSummaryList = billingSummaryMap.entrySet().stream()
            .map(entry -> new DashboardData.BillingSummary(entry.getKey(), entry.getValue()))
            .sorted((a, b) -> {
                double amountA = a.getAmount() != 0 ? a.getAmount() : a.getMonthToDateCost();
                double amountB = b.getAmount() != 0 ? b.getAmount() : b.getMonthToDateCost();
                return Double.compare(amountB, amountA);
            })
            .collect(Collectors.toList());
    aggregatedAccount.setBillingSummary(billingSummaryList);
    
    // Set savings summary
    List<DashboardData.SavingsSuggestion> savingsSuggestions = new ArrayList<>();
    if (totalPotentialSavings > 0) {
        savingsSuggestions.add(new DashboardData.SavingsSuggestion("Total Potential Savings", totalPotentialSavings));
    }
    aggregatedAccount.setSavingsSummary(new DashboardData.SavingsSummary(totalPotentialSavings, savingsSuggestions));
    
    // Set optimization summary
    aggregatedAccount.setOptimizationSummary(new DashboardData.OptimizationSummary(totalOptimizationSavings, totalCriticalAlerts));
    
    // Set security score (average)
    int avgSecurityScore = (int) accountDataList.stream()
            .filter(d -> d.getSelectedAccount() != null)
            .mapToInt(d -> d.getSelectedAccount().getSecurityScore())
            .average()
            .orElse(100.0);
    aggregatedAccount.setSecurityScore(avgSecurityScore);
    
    // Set empty lists for account-specific recommendations (these will be hidden in UI)
    aggregatedAccount.setEc2Recommendations(Collections.emptyList());
    aggregatedAccount.setEbsRecommendations(Collections.emptyList());
    aggregatedAccount.setLambdaRecommendations(Collections.emptyList());
    aggregatedAccount.setCostAnomalies(Collections.emptyList());
    aggregatedAccount.setWastedResources(Collections.emptyList());
    aggregatedAccount.setSecurityInsights(Collections.emptyList());
    aggregatedAccount.setReservationAnalysis(null);
    aggregatedAccount.setReservationPurchaseRecommendations(Collections.emptyList());
    aggregatedAccount.setServiceQuotas(Collections.emptyList());
    
    aggregated.setSelectedAccount(aggregatedAccount);
    return aggregated;
}

/**
 * Helper method to normalize month strings for consistent parsing.
 * Converts variations like "Sept" to "Sep", "June" to "Jun", etc.
 */
private String normalizeMonthString(String monthStr) {
    return monthStr
            .replace("Sept ", "Sep ")
            .replace("June ", "Jun ")
            .replace("July ", "Jul ")
            .replace("January ", "Jan ")
            .replace("February ", "Feb ")
            .replace("March ", "Mar ")
            .replace("April ", "Apr ")
            .replace("August ", "Aug ")
            .replace("September ", "Sep ")
            .replace("October ", "Oct ")
            .replace("November ", "Nov ")
            .replace("December ", "Dec ");
}




    public DashboardData getDashboardData(String accountId, boolean forceRefresh, ClientUserDetails userDetails) throws ExecutionException, InterruptedException, IOException {
        String cacheKey = "dashboardData-" + accountId;

        if (!forceRefresh) {
            Optional<DashboardData> cachedData = redisCache.get(cacheKey, DashboardData.class);
            if (cachedData.isPresent()) {
                return cachedData.get();
            }
        }

        CloudAccount account = getAccount(accountId);
        DashboardData freshData;

        if ("GCP".equals(account.getProvider())) {
            GcpDashboardData gcpData = gcpDataService.getDashboardData(account.getGcpProjectId(), forceRefresh)
                    .exceptionally(ex -> {
                        logger.error("Failed to get a complete GCP dashboard data object for account {}. Returning partial data.", account.getGcpProjectId(), ex);
                        return new GcpDashboardData(); // Return empty DTO on failure
                    })
                    .get();
            freshData = mapGcpDataToDashboardData(gcpData, account);
        } else {
            freshData = getAwsDashboardData(account, forceRefresh, userDetails);
        }

        redisCache.put(cacheKey, freshData);
        return freshData;
    }

    private DashboardData mapGcpDataToDashboardData(GcpDashboardData gcpData, CloudAccount account) {
        DashboardData data = new DashboardData();

        DashboardData.Account mainAccount = new DashboardData.Account();
        mainAccount.setId(account.getGcpProjectId());
        mainAccount.setName(account.getAccountName());

        mainAccount.setResourceInventory(gcpData.getResourceInventory());
        mainAccount.setIamResources(gcpData.getIamResources());
        mainAccount.setSecurityScore(gcpData.getSecurityScore());
        mainAccount.setSecurityInsights(gcpData.getSecurityInsights());
        mainAccount.setSavingsSummary(gcpData.getSavingsSummary());
        mainAccount.setMonthToDateSpend(gcpData.getMonthToDateSpend());
        mainAccount.setForecastedSpend(gcpData.getForecastedSpend());
        mainAccount.setLastMonthSpend(gcpData.getLastMonthSpend());
        mainAccount.setOptimizationSummary(gcpData.getOptimizationSummary());
        mainAccount.setRegionStatus(gcpData.getRegionStatus());

        List<String> costLabels = gcpData.getCostHistory().stream().map(c -> c.getName()).collect(Collectors.toList());
        List<Double> costValues = gcpData.getCostHistory().stream().map(c -> c.getAmount()).collect(Collectors.toList());
        List<Boolean> costAnomalies = gcpData.getCostHistory().stream().map(c -> c.isAnomaly()).collect(Collectors.toList());
        mainAccount.setCostHistory(new DashboardData.CostHistory(costLabels, costValues, costAnomalies));
        List<DashboardData.BillingSummary> billingSummary = gcpData.getBillingSummary().stream()
                .map(b -> new DashboardData.BillingSummary(b.getName(), b.getAmount()))
                .collect(Collectors.toList());
        mainAccount.setBillingSummary(billingSummary);
        List<DashboardData.OptimizationRecommendation> gceRecs = gcpData.getRightsizingRecommendations().stream()
                .map(rec -> new DashboardData.OptimizationRecommendation(
                        "GCE", rec.getResourceName(), rec.getCurrentMachineType(),
                        rec.getRecommendedMachineType(), rec.getMonthlySavings(), "Rightsizing opportunity", 0.0, 0.0))
                .collect(Collectors.toList());
        mainAccount.setEc2Recommendations(gceRecs);
        List<DashboardData.WastedResource> wastedResources = gcpData.getWastedResources().stream()
                .map(waste -> new DashboardData.WastedResource(
                        waste.getResourceName(), waste.getResourceName(), waste.getType(),
                        waste.getLocation(), waste.getMonthlySavings(), "Idle Resource"))
                .collect(Collectors.toList());
        mainAccount.setWastedResources(wastedResources);
        mainAccount.setCloudWatchStatus(new DashboardData.CloudWatchStatus(0,0,0));
        mainAccount.setCostAnomalies(Collections.emptyList());
        mainAccount.setEbsRecommendations(Collections.emptyList());
        mainAccount.setLambdaRecommendations(Collections.emptyList());

        data.setSelectedAccount(mainAccount);

        List<DashboardData.Account> availableAccounts = cloudAccountRepository.findAll().stream()
                .map(acc -> new DashboardData.Account(
                        "AWS".equals(acc.getProvider()) ? acc.getAwsAccountId() : acc.getGcpProjectId(),
                        acc.getAccountName(),
                        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, 0, 0.0, 0.0, 0.0
                ))
                .collect(Collectors.toList());
        data.setAvailableAccounts(availableAccounts);

        return data;
    }


    private DashboardData getAwsDashboardData(CloudAccount account, boolean forceRefresh, ClientUserDetails userDetails) throws ExecutionException, InterruptedException {
        logger.info("--- LAUNCHING OPTIMIZED ASYNC DATA FETCH FROM AWS for account {} ---", account.getAwsAccountId());

        CompletableFuture<List<DashboardData.RegionStatus>> activeRegionsFuture = cloudListService.getRegionStatusForAccount(account, forceRefresh);

        return activeRegionsFuture.thenCompose(activeRegions -> {
            if (activeRegions == null) {
                return CompletableFuture.completedFuture(new DashboardData());
            }

            CompletableFuture<List<DashboardData.ServiceGroupDto>> groupedResourcesFuture = cloudListService.getAllResourcesGrouped(account.getAwsAccountId(), forceRefresh);

            CompletableFuture<DashboardData.ResourceInventory> inventoryFuture = getResourceInventory(groupedResourcesFuture);
            CompletableFuture<DashboardData.CloudWatchStatus> cwStatusFuture = getCloudWatchStatus(account, activeRegions, forceRefresh);
            CompletableFuture<List<DashboardData.OptimizationRecommendation>> ec2RecsFuture = optimizationService.getEc2InstanceRecommendations(account, activeRegions, forceRefresh);
            CompletableFuture<List<DashboardData.OptimizationRecommendation>> ebsRecsFuture = optimizationService.getEbsVolumeRecommendations(account, activeRegions, forceRefresh);
            CompletableFuture<List<DashboardData.OptimizationRecommendation>> lambdaRecsFuture = optimizationService.getLambdaFunctionRecommendations(account, activeRegions, forceRefresh);
            CompletableFuture<List<DashboardData.WastedResource>> wastedResourcesFuture = optimizationService.getWastedResources(account, activeRegions, forceRefresh);
            CompletableFuture<List<DashboardData.SecurityFinding>> securityFindingsFuture = securityService.getComprehensiveSecurityFindings(account, activeRegions, forceRefresh);
            CompletableFuture<List<ReservationInventoryDto>> reservationInventoryFuture = reservationService.getReservationInventory(account, activeRegions, forceRefresh);
            CompletableFuture<DashboardData.CostHistory> costHistoryFuture = finOpsService.getCostHistory(account, forceRefresh);
            CompletableFuture<List<DashboardData.BillingSummary>> billingFuture = finOpsService.getBillingSummary(account, forceRefresh);
            CompletableFuture<DashboardData.IamResources> iamFuture = getIamResources(account, forceRefresh);
            CompletableFuture<List<DashboardData.CostAnomaly>> anomaliesFuture = finOpsService.getCostAnomalies(account, forceRefresh);
            CompletableFuture<DashboardData.ReservationAnalysis> reservationFuture = reservationService.getReservationAnalysis(account, forceRefresh);
            CompletableFuture<List<DashboardData.ReservationPurchaseRecommendation>> reservationPurchaseFuture = reservationService.getReservationPurchaseRecommendations(account, "ONE_YEAR", "NO_UPFRONT", "THIRTY_DAYS", "STANDARD", forceRefresh);
            CompletableFuture<List<DashboardData.ServiceQuotaInfo>> vpcQuotaInfoFuture = getServiceQuotaInfo(account, activeRegions, groupedResourcesFuture, "vpc", "L-F678F1CE");

            CompletableFuture<DashboardData.SavingsSummary> savingsFuture = getSavingsSummary(
                    wastedResourcesFuture, ec2RecsFuture, ebsRecsFuture, lambdaRecsFuture
            );

            return CompletableFuture.allOf(
                    inventoryFuture, cwStatusFuture, ec2RecsFuture, ebsRecsFuture, lambdaRecsFuture,
                    wastedResourcesFuture, securityFindingsFuture, costHistoryFuture, billingFuture,
                    iamFuture, savingsFuture, anomaliesFuture, reservationFuture, reservationPurchaseFuture,
                    reservationInventoryFuture, vpcQuotaInfoFuture
            ).thenApply(v -> {
                logger.info("--- ALL ASYNC DATA FETCHES COMPLETE for account {}, assembling DTO ---", account.getAwsAccountId());

                List<DashboardData.WastedResource> wastedResources = wastedResourcesFuture.join();
                List<DashboardData.OptimizationRecommendation> ec2Recs = ec2RecsFuture.join();
                List<DashboardData.OptimizationRecommendation> ebsRecs = ebsRecsFuture.join();
                List<DashboardData.OptimizationRecommendation> lambdaRecs = lambdaRecsFuture.join();
                List<DashboardData.CostAnomaly> anomalies = anomaliesFuture.join();
                List<DashboardData.SecurityFinding> securityFindings = securityFindingsFuture.join();
                List<DashboardData.ServiceQuotaInfo> vpcQuotas = vpcQuotaInfoFuture.join();

                List<DashboardData.SecurityInsight> securityInsights = securityFindings.stream()
                        .collect(Collectors.groupingBy(DashboardData.SecurityFinding::getCategory, Collectors.groupingBy(DashboardData.SecurityFinding::getSeverity, Collectors.counting())))
                        .entrySet().stream()
                        .map(entry -> new DashboardData.SecurityInsight(
                                String.format("%s has potential issues", entry.getKey()),
                                entry.getKey(),
                                entry.getValue().keySet().stream().findFirst().orElse("INFO"),
                                entry.getValue().values().stream().mapToInt(Long::intValue).sum()
                        )).collect(Collectors.toList());

                DashboardData.OptimizationSummary optimizationSummary = getOptimizationSummary(
                        wastedResources, ec2Recs, ebsRecs, lambdaRecs, anomalies
                );

                int securityScore = calculateSecurityScore(securityFindings);

                DashboardData data = new DashboardData();
                DashboardData.Account mainAccount = new DashboardData.Account(
                        account.getAwsAccountId(), account.getAccountName(),
                        activeRegions, inventoryFuture.join(), cwStatusFuture.join(), securityInsights,
                        costHistoryFuture.join(), billingFuture.join(), iamFuture.join(), savingsFuture.join(),
                        ec2Recs, anomalies, ebsRecs, lambdaRecs,
                        reservationFuture.join(), reservationPurchaseFuture.join(),
                        optimizationSummary, wastedResources, vpcQuotas,
                        securityScore, 0.0, 0.0, 0.0
                );

                data.setSelectedAccount(mainAccount);
                if (userDetails != null) {
                    boolean isAdmin = userDetails.getAuthorities().stream()
                            .anyMatch(role -> "ROLE_BILLOPS_ADMIN".equals(role.getAuthority()));

                    List<CloudAccount> userAccounts;
                    if (isAdmin) {
                        userAccounts = cloudAccountRepository.findAll();
                    } else {
                        userAccounts = cloudAccountRepository.findByClientId(userDetails.getClientId());
                    }

                    List<DashboardData.Account> availableAccounts = userAccounts.stream()
                            .map(acc -> new DashboardData.Account(
                                    "AWS".equals(acc.getProvider()) ? acc.getAwsAccountId() : acc.getGcpProjectId(),
                                    acc.getAccountName(),
                                    Collections.emptyList(),
                                    null, null, Collections.emptyList(), null, Collections.emptyList(), null, null,
                                    Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                                    null, Collections.emptyList(), null, Collections.emptyList(), Collections.emptyList(),
                                    100, 0.0, 0.0, 0.0
                            ))
                            .collect(Collectors.toList());
                    data.setAvailableAccounts(availableAccounts);
                }

                return data;
            });
        }).get();
    }


    private CompletableFuture<DashboardData.ResourceInventory> getResourceInventory(CompletableFuture<List<DashboardData.ServiceGroupDto>> groupedResourcesFuture) {
        return groupedResourcesFuture.thenApply(groupedResources -> {
            DashboardData.ResourceInventory inventory = new DashboardData.ResourceInventory();
            Map<String, Integer> counts = groupedResources.stream()
                    .collect(Collectors.toMap(
                            DashboardData.ServiceGroupDto::getServiceType,
                            group -> group.getResources().size()
                    ));

            inventory.setVpc(counts.getOrDefault("VPC", 0));
            inventory.setEcs(counts.getOrDefault("ECS Cluster", 0));
            inventory.setEc2(counts.getOrDefault("EC2 Instance", 0));
            inventory.setKubernetes(counts.getOrDefault("EKS Cluster", 0));
            inventory.setLambdas(counts.getOrDefault("Lambda Function", 0));
            inventory.setEbsVolumes(counts.getOrDefault("EBS Volume", 0));
            inventory.setImages(counts.getOrDefault("AMI", 0));
            inventory.setSnapshots(counts.getOrDefault("Snapshot", 0));
            inventory.setS3Buckets(counts.getOrDefault("S3 Bucket", 0));
            inventory.setRdsInstances(counts.getOrDefault("RDS Instance", 0));
            inventory.setRoute53Zones(counts.getOrDefault("Route 53 Zone", 0));
            inventory.setLoadBalancers(counts.getOrDefault("Load Balancer", 0));
            inventory.setLightsail(counts.getOrDefault("Lightsail Instance", 0));
            inventory.setAmplify(counts.getOrDefault("Amplify App", 0));
            return inventory;
        });
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<DashboardData.CloudWatchStatus> getCloudWatchStatus(CloudAccount account, List<DashboardData.RegionStatus> activeRegions, boolean forceRefresh) {
        String cacheKey = "cloudwatchStatus-" + account.getAwsAccountId();
        if (!forceRefresh) {
            Optional<DashboardData.CloudWatchStatus> cachedData = redisCache.get(cacheKey, DashboardData.CloudWatchStatus.class);
            if (cachedData.isPresent()) {
                return CompletableFuture.completedFuture(cachedData.get());
            }
        }

        DashboardData.CloudWatchStatus status = new DashboardData.CloudWatchStatus(0, 0, 0);
        redisCache.put(cacheKey, status);
        return CompletableFuture.completedFuture(status);
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<DashboardData.ServiceQuotaInfo>> getServiceQuotaInfo(CloudAccount account, List<DashboardData.RegionStatus> activeRegions, CompletableFuture<List<DashboardData.ServiceGroupDto>> groupedResourcesFuture, String serviceCode, String quotaCode) {
        return groupedResourcesFuture.thenCompose(groupedResources -> {
            Map<String, Long> vpcCounts = groupedResources.stream()
                    .filter(g -> "VPC".equals(g.getServiceType()))
                    .flatMap(g -> g.getResources().stream())
                    .collect(Collectors.groupingBy(com.xammer.cloud.dto.ResourceDto::getRegion, Collectors.counting()));

            List<CompletableFuture<DashboardData.ServiceQuotaInfo>> futures = activeRegions.stream()
                    .map(region -> CompletableFuture.supplyAsync(() -> {
                        try {
                            ServiceQuotasClient sqClient = awsClientProvider.getServiceQuotasClient(account, region.getRegionId());
                            ListServiceQuotasRequest listRequest = ListServiceQuotasRequest.builder()
                                    .serviceCode(serviceCode)
                                    .build();
                            ListServiceQuotasResponse listResponse = sqClient.listServiceQuotas(listRequest);
                            Optional<ServiceQuota> quota = listResponse.quotas().stream()
                                    .filter(q -> q.quotaCode().equals(quotaCode))
                                    .findFirst();

                            return quota.map(serviceQuota -> {
                                double currentCount = vpcCounts.getOrDefault(region.getRegionId(), 0L).doubleValue();
                                double utilization = (serviceQuota.value() > 0) ? (currentCount / serviceQuota.value()) * 100.0 : 0.0;
                                return new DashboardData.ServiceQuotaInfo(
                                        serviceQuota.quotaName(),
                                        serviceQuota.value(),
                                        utilization,
                                        region.getRegionId()
                                );
                            }).orElse(null);
                        } catch (Exception e) {
                            logger.error("Failed to get quota info for service {} in region {}.", serviceCode, region.getRegionId(), e);
                            return null;
                        }
                    }))
                    .collect(Collectors.toList());

            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .thenApply(v -> futures.stream()
                            .map(CompletableFuture::join)
                            .filter(java.util.Objects::nonNull)
                            .collect(Collectors.toList()));
        });
    }


    @Async("awsTaskExecutor")
    public CompletableFuture<DashboardData.IamResources> getIamResources(CloudAccount account, boolean forceRefresh) {
        String cacheKey = "iamResources-" + account.getAwsAccountId();
        if (!forceRefresh) {
            Optional<DashboardData.IamResources> cachedData = redisCache.get(cacheKey, DashboardData.IamResources.class);
            if (cachedData.isPresent()) {
                return CompletableFuture.completedFuture(cachedData.get());
            }
        }

        IamClient iam = awsClientProvider.getIamClient(account);
        logger.info("Fetching IAM resources for account {}...", account.getAwsAccountId());
        int users = 0, groups = 0, policies = 0, roles = 0;
        try { users = iam.listUsers().users().size(); } catch (Exception e) { logger.error("IAM check failed for Users on account {}", account.getAwsAccountId(), e); }
        try { groups = iam.listGroups().groups().size(); } catch (Exception e) { logger.error("IAM check failed for Groups on account {}", account.getAwsAccountId(), e); }
        try { policies = iam.listPolicies(r -> r.scope(PolicyScopeType.LOCAL)).policies().size(); } catch (Exception e) { logger.error("IAM check failed for Policies on account {}", account.getAwsAccountId(), e); }
        try { roles = iam.listRoles().roles().size(); } catch (Exception e) { logger.error("IAM check failed for Roles on account {}", account.getAwsAccountId(), e); }

        DashboardData.IamResources resources = new DashboardData.IamResources(users, groups, policies, roles);
        redisCache.put(cacheKey, resources);
        return CompletableFuture.completedFuture(resources);
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<DashboardData.SavingsSummary> getSavingsSummary(
            CompletableFuture<List<DashboardData.WastedResource>> wastedFuture,
            CompletableFuture<List<DashboardData.OptimizationRecommendation>> ec2RecsFuture,
            CompletableFuture<List<DashboardData.OptimizationRecommendation>> ebsRecsFuture,
            CompletableFuture<List<DashboardData.OptimizationRecommendation>> lambdaRecsFuture) {

        return CompletableFuture.allOf(wastedFuture, ec2RecsFuture, ebsRecsFuture, lambdaRecsFuture)
                .thenApply(v -> {
                    double wasteSavings = wastedFuture.join().stream()
                            .mapToDouble(DashboardData.WastedResource::getMonthlySavings)
                            .sum();

                    double rightsizingSavings = Stream.of(ec2RecsFuture.join(), ebsRecsFuture.join(), lambdaRecsFuture.join())
                            .flatMap(List::stream)
                            .mapToDouble(DashboardData.OptimizationRecommendation::getEstimatedMonthlySavings)
                            .sum();

                    List<DashboardData.SavingsSuggestion> suggestions = new ArrayList<>();
                    if (rightsizingSavings > 0) {
                        suggestions.add(new DashboardData.SavingsSuggestion("Rightsizing", rightsizingSavings));
                    }
                    if (wasteSavings > 0) {
                        suggestions.add(new DashboardData.SavingsSuggestion("Waste Elimination", wasteSavings));
                    }

                    double totalPotential = wasteSavings + rightsizingSavings;

                    return new DashboardData.SavingsSummary(totalPotential, suggestions);
                });
    }

    private DashboardData.OptimizationSummary getOptimizationSummary(
            List<DashboardData.WastedResource> wastedResources,
            List<DashboardData.OptimizationRecommendation> ec2Recs,
            List<DashboardData.OptimizationRecommendation> ebsRecs,
            List<DashboardData.OptimizationRecommendation> lambdaRecs,
            List<DashboardData.CostAnomaly> anomalies
    ) {
        double rightsizingSavings = Stream.of(ec2Recs, ebsRecs, lambdaRecs)
                .flatMap(List::stream)
                .mapToDouble(DashboardData.OptimizationRecommendation::getEstimatedMonthlySavings)
                .sum();

        double wasteSavings = wastedResources.stream()
                .mapToDouble(DashboardData.WastedResource::getMonthlySavings)
                .sum();

        double totalSavings = rightsizingSavings + wasteSavings;
        long criticalAlerts = (anomalies != null ? anomalies.size() : 0) + ec2Recs.size() + ebsRecs.size() + lambdaRecs.size();
        return new DashboardData.OptimizationSummary(totalSavings, criticalAlerts);
    }

    private int calculateSecurityScore(List<DashboardData.SecurityFinding> findings) {
        if (findings == null || findings.isEmpty()) {
            return 100;
        }
        Map<String, Long> counts = findings.stream()
                .collect(Collectors.groupingBy(DashboardData.SecurityFinding::getSeverity, Collectors.counting()));

        long criticalWeight = 5;
        long highWeight = 2;
        long mediumWeight = 1;
        long lowWeight = 0;

        long weightedScore = (counts.getOrDefault("CRITICAL", 0L) * criticalWeight) +
                (counts.getOrDefault("HIGH", 0L) * highWeight) +
                (counts.getOrDefault("MEDIUM", 0L) * mediumWeight) +
                (counts.getOrDefault("LOW", 0L) * lowWeight);

        double score = 100.0 / (1 + 0.1 * weightedScore);

        return Math.max(0, (int) Math.round(score));
    }
}