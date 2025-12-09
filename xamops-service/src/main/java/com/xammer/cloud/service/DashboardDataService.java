package com.xammer.cloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.gcp.*;
import com.xammer.cloud.dto.ReservationInventoryDto;
import com.xammer.cloud.repository.CachedDataRepository;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
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
    private final CachedDataRepository cachedDataRepository;
    private final CostService costService;

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
            ObjectMapper objectMapper,
            CachedDataRepository cachedDataRepository,
            @Lazy CostService costService) {
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
        this.cachedDataRepository = cachedDataRepository;
        this.costService = costService;
    }

    private CloudAccount getAccount(String accountId) {
        if (!StringUtils.hasText(accountId)) {
            throw new IllegalArgumentException("Account ID cannot be null or empty.");
        }

        Optional<CloudAccount> azureAccount = cloudAccountRepository.findByAzureSubscriptionId(accountId);
        if (azureAccount.isPresent()) {
            return azureAccount.get();
        }

        List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountIdOrGcpProjectId(accountId, accountId);
        if (accounts.isEmpty()) {
            throw new RuntimeException("Account not found in database: " + accountId);
        }
        return accounts.get(0);
    }

    /**
     * Modified to support AWS and GCP multi-account aggregation.
     * Enforces that all selected accounts belong to the same provider.
     */
    public DashboardData getMultiAccountDashboardData(List<String> accountIds, boolean forceRefresh,
            ClientUserDetails userDetails) throws ExecutionException, InterruptedException, IOException {
        logger.info("Starting multi-account dashboard data fetch for {} accounts", accountIds.size());

        if (accountIds.isEmpty()) {
            throw new IllegalArgumentException("Account list cannot be empty");
        }

        // ✅ STRICT VALIDATION: Ensure all accounts are from the same provider
        Set<String> providers = new HashSet<>();
        for (String accountId : accountIds) {
            try {
                CloudAccount account = getAccount(accountId);
                providers.add(account.getProvider());
            } catch (Exception e) {
                logger.error("Failed to validate account {}", accountId, e);
                throw new IllegalArgumentException("Invalid account ID: " + accountId);
            }
        }

        if (providers.size() > 1) {
            throw new IllegalArgumentException(
                    "Cannot mix cloud providers in multi-account view. Found: " +
                            String.join(", ", providers)
                            + ". Please select accounts from only one provider (AWS or GCP).");
        }

        // Determine provider from the first valid account
        CloudAccount firstAccount = getAccount(accountIds.get(0));
        String provider = firstAccount.getProvider();

        if ("GCP".equals(provider)) {
            return getMultiAccountGcpDashboardData(accountIds, forceRefresh, userDetails);
        } else if ("AWS".equals(provider)) {
            return getMultiAccountAwsDashboardData(accountIds, forceRefresh, userDetails);
        } else {
            throw new IllegalArgumentException("Multi-account view is not supported for provider: " + provider);
        }
    }

    /**
     * Logic for aggregating GCP accounts.
     */
    private DashboardData getMultiAccountGcpDashboardData(List<String> accountIds, boolean forceRefresh,
            ClientUserDetails userDetails) {
        logger.info("Executing GCP multi-account fetch for accounts: {}", accountIds);

        List<CloudAccount> validAccounts = new ArrayList<>();
        List<String> accountNames = new ArrayList<>();
        List<String> failedAccounts = new ArrayList<>();

        // Validate accounts
        for (String accountId : accountIds) {
            try {
                CloudAccount account = getAccount(accountId);
                if ("GCP".equals(account.getProvider())) {
                    validAccounts.add(account);
                    accountNames.add(account.getAccountName());
                } else {
                    logger.warn("Skipping non-GCP account {} in GCP multi-account mode", accountId);
                }
            } catch (Exception e) {
                logger.error("Failed to load account {}", accountId, e);
                failedAccounts.add(accountId);
            }
        }

        if (validAccounts.isEmpty()) {
            throw new RuntimeException("No valid GCP accounts found for multi-account dashboard");
        }

        // Parallel fetch
        List<CompletableFuture<GcpDashboardData>> futures = validAccounts.stream()
                .map(account -> gcpDataService.getDashboardData(account.getGcpProjectId(), forceRefresh)
                        .exceptionally(ex -> {
                            logger.error("Failed to fetch data for GCP project {}", account.getGcpProjectId(), ex);
                            failedAccounts.add(account.getGcpProjectId());
                            return new GcpDashboardData(); // Return empty object on failure to avoid blocking
                                                           // aggregation
                        }))
                .collect(Collectors.toList());

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        List<GcpDashboardData> accountDataList = futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList());

        DashboardData aggregatedData = aggregateGcpDashboardData(accountDataList, validAccounts, accountNames,
                failedAccounts);
        populateAvailableAccounts(aggregatedData, userDetails);

        return aggregatedData;
    }

    private DashboardData aggregateGcpDashboardData(List<GcpDashboardData> dataList,
            List<CloudAccount> accounts,
            List<String> accountNames,
            List<String> failedAccounts) {
        DashboardData aggregated = new DashboardData();
        aggregated.setMultiAccountView(true);
        aggregated.setSelectedAccountIds(
                accounts.stream().map(CloudAccount::getGcpProjectId).collect(Collectors.toList()));
        aggregated.setSelectedAccountNames(accountNames);
        aggregated.setFailedAccounts(failedAccounts);
        aggregated.setSelectedProvider("GCP");

        DashboardData.Account aggregatedAccount = new DashboardData.Account();
        aggregatedAccount.setId("multi-account-gcp");
        aggregatedAccount.setName(String.format("%d GCP Projects", accounts.size()));

        // Aggregate Metrics
        double totalMTD = 0.0;
        double totalForecast = 0.0;
        double totalLastMonth = 0.0;
        double totalOptimizationSavings = 0.0;
        long totalCriticalAlerts = 0;
        double totalPotentialSavings = 0.0;

        DashboardData.ResourceInventory aggregatedInventory = new DashboardData.ResourceInventory();
        DashboardData.IamResources aggregatedIam = new DashboardData.IamResources(0, 0, 0, 0);
        Set<String> uniqueRegions = new HashSet<>();
        Map<String, Double> costHistoryMap = new LinkedHashMap<>();
        Map<String, Double> billingSummaryMap = new HashMap<>();
        Map<String, DashboardData.SecurityInsight> securityInsightMap = new HashMap<>();
        List<Integer> securityScores = new ArrayList<>();

        for (GcpDashboardData data : dataList) {
            // Costs
            totalMTD += data.getMonthToDateSpend();
            totalForecast += data.getForecastedSpend();
            totalLastMonth += data.getLastMonthSpend();

            // Resources
            if (data.getResourceInventory() != null) {
                DashboardData.ResourceInventory inv = data.getResourceInventory();
                aggregatedInventory.setEc2(aggregatedInventory.getEc2() + inv.getEc2()); // Compute Engine
                aggregatedInventory.setS3Buckets(aggregatedInventory.getS3Buckets() + inv.getS3Buckets()); // Cloud
                                                                                                           // Storage
                aggregatedInventory.setRdsInstances(aggregatedInventory.getRdsInstances() + inv.getRdsInstances()); // Cloud
                                                                                                                    // SQL
                aggregatedInventory.setKubernetes(aggregatedInventory.getKubernetes() + inv.getKubernetes()); // GKE
                aggregatedInventory.setVpc(aggregatedInventory.getVpc() + inv.getVpc());
                aggregatedInventory.setRoute53Zones(aggregatedInventory.getRoute53Zones() + inv.getRoute53Zones()); // Cloud
                                                                                                                    // DNS
                aggregatedInventory.setLoadBalancers(aggregatedInventory.getLoadBalancers() + inv.getLoadBalancers());
                aggregatedInventory.setFirewalls(aggregatedInventory.getFirewalls() + inv.getFirewalls());
                aggregatedInventory
                        .setCloudNatRouters(aggregatedInventory.getCloudNatRouters() + inv.getCloudNatRouters());
                aggregatedInventory.setArtifactRepositories(
                        aggregatedInventory.getArtifactRepositories() + inv.getArtifactRepositories());
                aggregatedInventory.setKmsKeys(aggregatedInventory.getKmsKeys() + inv.getKmsKeys());
                aggregatedInventory
                        .setCloudFunctions(aggregatedInventory.getCloudFunctions() + inv.getCloudFunctions());
                aggregatedInventory.setCloudBuildTriggers(
                        aggregatedInventory.getCloudBuildTriggers() + inv.getCloudBuildTriggers());
                aggregatedInventory.setSecretManagerSecrets(
                        aggregatedInventory.getSecretManagerSecrets() + inv.getSecretManagerSecrets());
                aggregatedInventory.setCloudArmorPolicies(
                        aggregatedInventory.getCloudArmorPolicies() + inv.getCloudArmorPolicies());
            }

            // IAM
            if (data.getIamResources() != null) {
                aggregatedIam.setUsers(aggregatedIam.getUsers() + data.getIamResources().getUsers());
                aggregatedIam.setRoles(aggregatedIam.getRoles() + data.getIamResources().getRoles());
                // Groups/Policies might not be populated in basic GCP call, but sum if present
                aggregatedIam.setGroups(aggregatedIam.getGroups() + data.getIamResources().getGroups());
                aggregatedIam
                        .setCustomerManagedPolicies(aggregatedIam.getPolicies() + data.getIamResources().getPolicies());
            }

            // Security
            if (data.getSecurityScore() > 0) {
                securityScores.add(data.getSecurityScore());
            }
            if (data.getSecurityInsights() != null) {
                for (DashboardData.SecurityInsight insight : data.getSecurityInsights()) {
                    String key = insight.getCategory();
                    securityInsightMap.merge(key, insight, (existing, valid) -> new DashboardData.SecurityInsight(
                            existing.getTitle(),
                            existing.getCategory(),
                            existing.getSeverity(),
                            existing.getCount() + valid.getCount()));
                }
            }

            // Optimization
            if (data.getOptimizationSummary() != null) {
                totalOptimizationSavings += data.getOptimizationSummary().getTotalPotentialSavings();
                totalCriticalAlerts += data.getOptimizationSummary().getCriticalAlerts();
            }
            if (data.getSavingsSummary() != null) {
                totalPotentialSavings += data.getSavingsSummary().getTotalPotentialSavings();
            }

            // Regions
            if (data.getRegionStatus() != null) {
                data.getRegionStatus().forEach(r -> uniqueRegions.add(r.getRegionId()));
            }

            // Cost History
            if (data.getCostHistory() != null) {
                for (GcpCostDto cost : data.getCostHistory()) {
                    costHistoryMap.merge(cost.getName(), cost.getAmount(), Double::sum);
                }
            }

            // Billing Summary
            if (data.getBillingSummary() != null) {
                for (GcpCostDto bill : data.getBillingSummary()) {
                    billingSummaryMap.merge(bill.getName(), bill.getAmount(), Double::sum);
                }
            }
        }

        // Set Aggregated Values
        aggregatedAccount.setMonthToDateSpend(totalMTD);
        aggregatedAccount.setForecastedSpend(totalForecast);
        aggregatedAccount.setLastMonthSpend(totalLastMonth);
        aggregatedAccount.setResourceInventory(aggregatedInventory);
        aggregatedAccount.setIamResources(aggregatedIam);
        aggregatedAccount.setSecurityScore(securityScores.isEmpty() ? 100
                : (int) securityScores.stream().mapToInt(Integer::intValue).average().orElse(100));
        aggregatedAccount.setSecurityInsights(new ArrayList<>(securityInsightMap.values()));

        aggregatedAccount.setOptimizationSummary(
                new DashboardData.OptimizationSummary(totalOptimizationSavings, totalCriticalAlerts));

        List<DashboardData.SavingsSuggestion> savingsSuggestions = new ArrayList<>();
        if (totalPotentialSavings > 0) {
            savingsSuggestions
                    .add(new DashboardData.SavingsSuggestion("Total Potential Savings", totalPotentialSavings));
        }
        aggregatedAccount
                .setSavingsSummary(new DashboardData.SavingsSummary(totalPotentialSavings, savingsSuggestions));

        // Regions
        List<DashboardData.RegionStatus> regionStatusList = uniqueRegions.stream()
                .map(regionId -> new DashboardData.RegionStatus(regionId, true))
                .collect(Collectors.toList());
        aggregatedAccount.setRegionStatus(regionStatusList);

        // Cost History Processing (Sorting by month)
        List<String> costLabels = new ArrayList<>(costHistoryMap.keySet());
        costLabels.sort(this::compareMonthStrings); // Reuse or duplicate helper
        List<Double> costValues = costLabels.stream().map(costHistoryMap::get).collect(Collectors.toList());
        List<Boolean> costAnomalies = costLabels.stream().map(l -> false).collect(Collectors.toList());
        aggregatedAccount.setCostHistory(new DashboardData.CostHistory(costLabels, costValues, costAnomalies));

        // Billing Summary Processing
        List<DashboardData.BillingSummary> billingSummaryList = billingSummaryMap.entrySet().stream()
                .map(entry -> new DashboardData.BillingSummary(entry.getKey(), entry.getValue()))
                .sorted((a, b) -> Double.compare(b.getAmount(), a.getAmount()))
                .collect(Collectors.toList());
        aggregatedAccount.setBillingSummary(billingSummaryList);

        // Empty Lists for specific items (too large to aggregate all)
        aggregatedAccount.setEc2Recommendations(Collections.emptyList());
        aggregatedAccount.setWastedResources(Collections.emptyList());
        aggregatedAccount.setCloudWatchStatus(new DashboardData.CloudWatchStatus(0, 0, 0));

        aggregated.setSelectedAccount(aggregatedAccount);
        return aggregated;
    }

    /**
     * Existing logic for AWS multi-account, extracted to a method.
     */
    private DashboardData getMultiAccountAwsDashboardData(List<String> accountIds, boolean forceRefresh,
            ClientUserDetails userDetails) throws ExecutionException, InterruptedException, IOException {
        logger.info("Executing AWS multi-account fetch for accounts: {}", accountIds);

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
                    logger.warn("Skipping non-AWS account {} in AWS multi-account mode", accountId);
                }
            } catch (Exception e) {
                logger.error("Failed to load account {}", accountId, e);
                failedAccounts.add(accountId);
            }
        }

        if (validAccounts.isEmpty()) {
            throw new RuntimeException("No valid AWS accounts found for multi-account dashboard");
        }

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

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        List<DashboardData> accountDataList = futures.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        if (accountDataList.isEmpty()) {
            throw new RuntimeException("Failed to fetch data from all selected AWS accounts");
        }

        DashboardData aggregatedData = aggregateDashboardData(accountDataList, validAccounts, accountNames,
                failedAccounts);
        populateAvailableAccounts(aggregatedData, userDetails);

        return aggregatedData;
    }

    // Helper to compare month strings (e.g. "Jan 2023", "2023-01")
    private int compareMonthStrings(String a, String b) {
        try {
            String normalizedA = normalizeMonthString(a);
            String normalizedB = normalizeMonthString(b);

            DateTimeFormatter formatter;
            if (normalizedA.contains(" ")) {
                formatter = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);
            } else if (normalizedA.contains("-")) {
                formatter = DateTimeFormatter.ofPattern("yyyy-MM");
            } else {
                return a.compareTo(b);
            }

            YearMonth dateA = YearMonth.parse(normalizedA, formatter);
            YearMonth dateB = YearMonth.parse(normalizedB, formatter);
            return dateA.compareTo(dateB);
        } catch (Exception e) {
            logger.debug("Could not parse month labels '{}' and '{}' as dates, using string comparison", a, b);
            return normalizeMonthString(a).compareTo(normalizeMonthString(b));
        }
    }

    private void populateAvailableAccounts(DashboardData data, ClientUserDetails userDetails) {
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
                            null, null, Collections.emptyList(), null, Collections.emptyList(), null, null, null,
                            Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                            Collections.emptyList(),
                            null, Collections.emptyList(), null, Collections.emptyList(), Collections.emptyList(),
                            100, 0.0, 0.0, 0.0))
                    .collect(Collectors.toList());
            data.setAvailableAccounts(availableAccounts);
        }
    }

    private DashboardData aggregateDashboardData(List<DashboardData> accountDataList,
            List<CloudAccount> accounts,
            List<String> accountNames,
            List<String> failedAccounts) {
        DashboardData aggregated = new DashboardData();
        aggregated.setMultiAccountView(true);
        aggregated.setSelectedAccountIds(
                accounts.stream().map(CloudAccount::getAwsAccountId).collect(Collectors.toList()));
        aggregated.setSelectedAccountNames(accountNames);
        aggregated.setFailedAccounts(failedAccounts);
        aggregated.setSelectedProvider("AWS");

        double totalMTD = 0.0;
        double totalForecast = 0.0;
        double totalLastMonth = 0.0;
        double totalPotentialSavings = 0.0;

        DashboardData.ResourceInventory aggregatedInventory = new DashboardData.ResourceInventory();

        int totalUsers = 0, totalGroups = 0, totalPolicies = 0, totalRoles = 0;
        int totalAlarms = 0, totalMetrics = 0, totalLogs = 0;

        Set<String> uniqueRegions = new HashSet<>();
        Map<String, Double> costHistoryMap = new LinkedHashMap<>();
        Map<String, Double> billingSummaryMap = new HashMap<>();

        double totalOptimizationSavings = 0.0;
        long totalCriticalAlerts = 0;

        for (DashboardData data : accountDataList) {
            DashboardData.Account account = data.getSelectedAccount();
            if (account == null)
                continue;

            totalMTD += account.getMonthToDateSpend();
            totalForecast += account.getForecastedSpend();
            totalLastMonth += account.getLastMonthSpend();

            if (account.getSavingsSummary() != null) {
                totalPotentialSavings += account.getSavingsSummary().getTotalPotentialSavings();
            }

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

            if (account.getIamResources() != null) {
                totalUsers += account.getIamResources().getUsers();
                totalGroups += account.getIamResources().getGroups();
                totalPolicies += account.getIamResources().getPolicies();
                totalRoles += account.getIamResources().getRoles();
            }

            if (account.getCloudWatchStatus() != null) {
                totalAlarms += account.getCloudWatchStatus().getAlarms();
                totalMetrics += account.getCloudWatchStatus().getMetrics();
                totalLogs += account.getCloudWatchStatus().getLogGroups();
            }

            if (account.getRegionStatus() != null) {
                account.getRegionStatus().forEach(region -> uniqueRegions.add(region.getRegionId()));
            }

            if (account.getCostHistory() != null && account.getCostHistory().getLabels() != null) {
                List<String> labels = account.getCostHistory().getLabels();
                List<Double> values = account.getCostHistory().getValues() != null
                        ? account.getCostHistory().getValues()
                        : account.getCostHistory().getCosts();

                for (int i = 0; i < labels.size() && i < values.size(); i++) {
                    String month = labels.get(i);
                    double amount = values.get(i);
                    costHistoryMap.put(month, costHistoryMap.getOrDefault(month, 0.0) + amount);
                }
            }

            if (account.getBillingSummary() != null) {
                for (DashboardData.BillingSummary billing : account.getBillingSummary()) {
                    String service = billing.getServiceName();
                    double amount = billing.getAmount() != 0 ? billing.getAmount() : billing.getMonthToDateCost();
                    billingSummaryMap.put(service, billingSummaryMap.getOrDefault(service, 0.0) + amount);
                }
            }

            if (account.getOptimizationSummary() != null) {
                totalOptimizationSavings += account.getOptimizationSummary().getTotalPotentialSavings();
                totalCriticalAlerts += account.getOptimizationSummary().getCriticalAlerts();
            }
        }

        DashboardData.Account aggregatedAccount = new DashboardData.Account();
        aggregatedAccount.setId("multi-account");
        aggregatedAccount.setName(String.format("%d AWS Accounts", accounts.size()));
        aggregatedAccount.setMonthToDateSpend(totalMTD);
        aggregatedAccount.setForecastedSpend(totalForecast);
        aggregatedAccount.setLastMonthSpend(totalLastMonth);
        aggregatedAccount.setResourceInventory(aggregatedInventory);

        aggregatedAccount
                .setIamResources(new DashboardData.IamResources(totalUsers, totalGroups, totalPolicies, totalRoles));

        aggregatedAccount.setCloudWatchStatus(new DashboardData.CloudWatchStatus(totalAlarms, totalMetrics, totalLogs));

        List<DashboardData.RegionStatus> regionStatusList = uniqueRegions.stream()
                .map(regionId -> new DashboardData.RegionStatus(regionId, true))
                .collect(Collectors.toList());
        aggregatedAccount.setRegionStatus(regionStatusList);

        List<String> costLabels = new ArrayList<>(costHistoryMap.keySet());

        costLabels.sort(this::compareMonthStrings);

        List<Double> costValues = costLabels.stream()
                .map(costHistoryMap::get)
                .collect(Collectors.toList());

        List<Boolean> costAnomalies = costLabels.stream()
                .map(label -> false)
                .collect(Collectors.toList());

        aggregatedAccount.setCostHistory(new DashboardData.CostHistory(costLabels, costValues, costAnomalies));

        List<DashboardData.BillingSummary> billingSummaryList = billingSummaryMap.entrySet().stream()
                .map(entry -> new DashboardData.BillingSummary(entry.getKey(), entry.getValue()))
                .sorted((a, b) -> {
                    double amountA = a.getAmount() != 0 ? a.getAmount() : a.getMonthToDateCost();
                    double amountB = b.getAmount() != 0 ? b.getAmount() : b.getMonthToDateCost();
                    return Double.compare(amountB, amountA);
                })
                .collect(Collectors.toList());
        aggregatedAccount.setBillingSummary(billingSummaryList);

        List<DashboardData.SavingsSuggestion> savingsSuggestions = new ArrayList<>();
        if (totalPotentialSavings > 0) {
            savingsSuggestions
                    .add(new DashboardData.SavingsSuggestion("Total Potential Savings", totalPotentialSavings));
        }
        aggregatedAccount
                .setSavingsSummary(new DashboardData.SavingsSummary(totalPotentialSavings, savingsSuggestions));

        aggregatedAccount.setOptimizationSummary(
                new DashboardData.OptimizationSummary(totalOptimizationSavings, totalCriticalAlerts));

        int avgSecurityScore = (int) accountDataList.stream()
                .filter(d -> d.getSelectedAccount() != null)
                .mapToInt(d -> d.getSelectedAccount().getSecurityScore())
                .average()
                .orElse(100.0);
        aggregatedAccount.setSecurityScore(avgSecurityScore);

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

    public DashboardData getDashboardData(String accountId, boolean forceRefresh, ClientUserDetails userDetails)
            throws ExecutionException, InterruptedException, IOException {
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
                        logger.error(
                                "Failed to get a complete GCP dashboard data object for account {}. Returning partial data.",
                                account.getGcpProjectId(), ex);
                        return new GcpDashboardData();
                    })
                    .get();
            freshData = mapGcpDataToDashboardData(gcpData, account);
        } else {
            freshData = getAwsDashboardData(account, forceRefresh, userDetails);
        }

        // Cache in Redis
        redisCache.put(cacheKey, freshData, 10);

        // ✅ Archive to Postgres for Superset
        try {
            String dbKey = "DASHBOARD_DATA::" + accountId + "::" + LocalDate.now();
            String json = objectMapper.writeValueAsString(freshData);

            CachedData archivalData = cachedDataRepository.findById(dbKey).orElse(new CachedData());
            archivalData.setCacheKey(dbKey);
            archivalData.setJsonData(json);
            archivalData.setLastUpdated(LocalDateTime.now());

            cachedDataRepository.save(archivalData);
            logger.info("✅ Archived Dashboard Data to DB for account {}", accountId);
        } catch (Exception e) {
            logger.error("❌ Failed to archive Dashboard Data", e);
        }

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
        List<Double> costValues = gcpData.getCostHistory().stream().map(c -> c.getAmount())
                .collect(Collectors.toList());
        List<Boolean> costAnomalies = gcpData.getCostHistory().stream().map(c -> c.isAnomaly())
                .collect(Collectors.toList());
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
        mainAccount.setCloudWatchStatus(new DashboardData.CloudWatchStatus(0, 0, 0));
        mainAccount.setCostAnomalies(Collections.emptyList());
        mainAccount.setEbsRecommendations(Collections.emptyList());
        mainAccount.setLambdaRecommendations(Collections.emptyList());

        data.setSelectedAccount(mainAccount);
        populateAvailableAccounts(data, null); // userDetails already passed in main flow or fetched inside

        return data;
    }

    private DashboardData getAwsDashboardData(CloudAccount account, boolean forceRefresh, ClientUserDetails userDetails)
            throws ExecutionException, InterruptedException {
        logger.info("--- LAUNCHING OPTIMIZED ASYNC DATA FETCH FROM AWS for account {} ---", account.getAwsAccountId());

        CompletableFuture<List<DashboardData.RegionStatus>> activeRegionsFuture = cloudListService
                .getRegionStatusForAccount(account, forceRefresh);

        return activeRegionsFuture.thenCompose((List<DashboardData.RegionStatus> activeRegions) -> {
            if (activeRegions == null) {
                return CompletableFuture.completedFuture(new DashboardData());
            }

            CompletableFuture<List<DashboardData.ServiceGroupDto>> groupedResourcesFuture = cloudListService
                    .getAllResourcesGrouped(account.getAwsAccountId(), forceRefresh);

            CompletableFuture<DashboardData.ResourceInventory> inventoryFuture = getResourceInventory(
                    groupedResourcesFuture);
            CompletableFuture<DashboardData.CloudWatchStatus> cwStatusFuture = getCloudWatchStatus(account,
                    activeRegions, forceRefresh);

            // Optimization Futures
            CompletableFuture<List<DashboardData.OptimizationRecommendation>> ec2RecsFuture = optimizationService
                    .getEc2InstanceRecommendations(account, activeRegions, forceRefresh);
            CompletableFuture<List<DashboardData.OptimizationRecommendation>> ebsRecsFuture = optimizationService
                    .getEbsVolumeRecommendations(account, activeRegions, forceRefresh);
            CompletableFuture<List<DashboardData.OptimizationRecommendation>> lambdaRecsFuture = optimizationService
                    .getLambdaFunctionRecommendations(account, activeRegions, forceRefresh);
            CompletableFuture<List<DashboardData.WastedResource>> wastedResourcesFuture = optimizationService
                    .getWastedResources(account, activeRegions, forceRefresh);

            // Security & Other Futures
            CompletableFuture<List<DashboardData.SecurityFinding>> securityFindingsFuture = securityService
                    .getComprehensiveSecurityFindings(account, activeRegions, forceRefresh);
            CompletableFuture<List<ReservationInventoryDto>> reservationInventoryFuture = reservationService
                    .getReservationInventory(account, activeRegions, forceRefresh);
            CompletableFuture<DashboardData.CostHistory> costHistoryFuture = finOpsService.getCostHistory(account,
                    forceRefresh);
            CompletableFuture<List<DashboardData.BillingSummary>> billingFuture = finOpsService
                    .getBillingSummary(account, forceRefresh);
            CompletableFuture<DashboardData.IamResources> iamFuture = getIamResources(account, forceRefresh);
            // ✅ NEW: Fetch detailed IAM Info
            CompletableFuture<DashboardData.IamDetail> iamDetailsFuture = getIamDetails(account, forceRefresh);

            CompletableFuture<List<DashboardData.CostAnomaly>> anomaliesFuture = finOpsService.getCostAnomalies(account,
                    forceRefresh);
            CompletableFuture<DashboardData.ReservationAnalysis> reservationFuture = reservationService
                    .getReservationAnalysis(account, forceRefresh);
            CompletableFuture<List<DashboardData.ReservationPurchaseRecommendation>> reservationPurchaseFuture = reservationService
                    .getReservationPurchaseRecommendations(account, "ONE_YEAR", "NO_UPFRONT", "THIRTY_DAYS", "STANDARD",
                            forceRefresh);
            CompletableFuture<List<DashboardData.ServiceQuotaInfo>> vpcQuotaInfoFuture = getServiceQuotaInfo(account,
                    activeRegions, groupedResourcesFuture, "vpc", "L-F678F1CE");

            // ✅ NEW: Cost Futures for Header Stats - Passing forceRefresh
            CompletableFuture<Double> mtdSpendFuture = costService.getTotalMonthToDateCost(account.getAwsAccountId(),
                    forceRefresh);
            CompletableFuture<Double> lastMonthSpendFuture = costService.getLastMonthSpend(account.getAwsAccountId(),
                    forceRefresh);

            CompletableFuture<DashboardData.SavingsSummary> savingsFuture = getSavingsSummary(
                    wastedResourcesFuture, ec2RecsFuture, ebsRecsFuture, lambdaRecsFuture);

            return CompletableFuture.allOf(
                    inventoryFuture, cwStatusFuture, ec2RecsFuture, ebsRecsFuture, lambdaRecsFuture,
                    wastedResourcesFuture, securityFindingsFuture, costHistoryFuture, billingFuture,
                    iamFuture, iamDetailsFuture, savingsFuture, anomaliesFuture, reservationFuture,
                    reservationPurchaseFuture,
                    reservationInventoryFuture, vpcQuotaInfoFuture,
                    mtdSpendFuture, lastMonthSpendFuture // ✅ Wait for cost futures
            ).thenApply(v -> {
                List<DashboardData.WastedResource> wastedResources = wastedResourcesFuture.join();
                List<DashboardData.OptimizationRecommendation> ec2Recs = ec2RecsFuture.join();
                List<DashboardData.OptimizationRecommendation> ebsRecs = ebsRecsFuture.join();
                List<DashboardData.OptimizationRecommendation> lambdaRecs = lambdaRecsFuture.join();
                List<DashboardData.CostAnomaly> anomalies = anomaliesFuture.join();
                List<DashboardData.SecurityFinding> securityFindings = securityFindingsFuture.join();
                List<DashboardData.ServiceQuotaInfo> vpcQuotas = vpcQuotaInfoFuture.join();

                // ✅ Get Cost Values
                Double mtdSpend = mtdSpendFuture.join();
                Double lastMonthSpend = lastMonthSpendFuture.join();

                // Calculate Simple Forecast (Linear Extrapolation)
                int dayOfMonth = LocalDate.now().getDayOfMonth();
                int daysInMonth = LocalDate.now().lengthOfMonth();
                Double forecastedSpend = (dayOfMonth > 0) ? (mtdSpend / dayOfMonth) * daysInMonth : 0.0;

                List<DashboardData.SecurityInsight> securityInsights = securityFindings.stream()
                        .collect(Collectors.groupingBy(DashboardData.SecurityFinding::getCategory,
                                Collectors.groupingBy(DashboardData.SecurityFinding::getSeverity,
                                        Collectors.counting())))
                        .entrySet().stream()
                        .map(entry -> new DashboardData.SecurityInsight(
                                String.format("%s has potential issues", entry.getKey()),
                                entry.getKey(),
                                entry.getValue().keySet().stream().findFirst().orElse("INFO"),
                                entry.getValue().values().stream().mapToInt(Long::intValue).sum()))
                        .collect(Collectors.toList());

                DashboardData.OptimizationSummary optimizationSummary = getOptimizationSummary(
                        wastedResources, ec2Recs, ebsRecs, lambdaRecs, anomalies);

                int securityScore = calculateSecurityScore(securityFindings);

                DashboardData data = new DashboardData();
                DashboardData.Account mainAccount = new DashboardData.Account(
                        account.getAwsAccountId(), account.getAccountName(),
                        activeRegions, inventoryFuture.join(), cwStatusFuture.join(), securityInsights,
                        costHistoryFuture.join(), billingFuture.join(), iamFuture.join(), iamDetailsFuture.join(),
                        savingsFuture.join(),
                        ec2Recs, anomalies, ebsRecs, lambdaRecs,
                        reservationFuture.join(), reservationPurchaseFuture.join(),
                        optimizationSummary, wastedResources, vpcQuotas,
                        securityScore,
                        mtdSpend, // ✅ Pass actual MTD Spend
                        forecastedSpend, // ✅ Pass calculated Forecast
                        lastMonthSpend // ✅ Pass actual Last Month Spend
                );

                data.setSelectedAccount(mainAccount);
                populateAvailableAccounts(data, userDetails);

                return data;
            });
        }).get();
    }

    // ... (getResourceInventory, getCloudWatchStatus, getServiceQuotaInfo remain
    // unchanged) ...

    private CompletableFuture<DashboardData.ResourceInventory> getResourceInventory(
            CompletableFuture<List<DashboardData.ServiceGroupDto>> groupedResourcesFuture) {
        return groupedResourcesFuture.thenApply(groupedResources -> {
            DashboardData.ResourceInventory inventory = new DashboardData.ResourceInventory();
            Map<String, Integer> counts = groupedResources.stream()
                    .collect(Collectors.toMap(
                            DashboardData.ServiceGroupDto::getServiceType,
                            group -> group.getResources().size()));

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
    public CompletableFuture<DashboardData.CloudWatchStatus> getCloudWatchStatus(CloudAccount account,
            List<DashboardData.RegionStatus> activeRegions, boolean forceRefresh) {
        String cacheKey = "cloudwatchStatus-" + account.getAwsAccountId();
        if (!forceRefresh) {
            Optional<DashboardData.CloudWatchStatus> cachedData = redisCache.get(cacheKey,
                    DashboardData.CloudWatchStatus.class);
            if (cachedData.isPresent()) {
                return CompletableFuture.completedFuture(cachedData.get());
            }
        }

        DashboardData.CloudWatchStatus status = new DashboardData.CloudWatchStatus(0, 0, 0);
        redisCache.put(cacheKey, status, 10);
        return CompletableFuture.completedFuture(status);
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<DashboardData.ServiceQuotaInfo>> getServiceQuotaInfo(CloudAccount account,
            List<DashboardData.RegionStatus> activeRegions,
            CompletableFuture<List<DashboardData.ServiceGroupDto>> groupedResourcesFuture, String serviceCode,
            String quotaCode) {
        return groupedResourcesFuture.thenCompose(groupedResources -> {
            Map<String, Long> vpcCounts = groupedResources.stream()
                    .filter(g -> "VPC".equals(g.getServiceType()))
                    .flatMap(g -> g.getResources().stream())
                    .collect(Collectors.groupingBy(com.xammer.cloud.dto.ResourceDto::getRegion, Collectors.counting()));

            List<CompletableFuture<DashboardData.ServiceQuotaInfo>> futures = activeRegions.stream()
                    .map(region -> CompletableFuture.supplyAsync(() -> {
                        try {
                            ServiceQuotasClient sqClient = awsClientProvider.getServiceQuotasClient(account,
                                    region.getRegionId());
                            ListServiceQuotasRequest listRequest = ListServiceQuotasRequest.builder()
                                    .serviceCode(serviceCode)
                                    .build();
                            ListServiceQuotasResponse listResponse = sqClient.listServiceQuotas(listRequest);
                            Optional<ServiceQuota> quota = listResponse.quotas().stream()
                                    .filter(q -> q.quotaCode().equals(quotaCode))
                                    .findFirst();

                            return quota.map(serviceQuota -> {
                                double currentCount = vpcCounts.getOrDefault(region.getRegionId(), 0L).doubleValue();
                                double utilization = (serviceQuota.value() > 0)
                                        ? (currentCount / serviceQuota.value()) * 100.0
                                        : 0.0;
                                return new DashboardData.ServiceQuotaInfo(
                                        serviceQuota.quotaName(),
                                        serviceQuota.value(),
                                        utilization,
                                        region.getRegionId());
                            }).orElse(null);
                        } catch (Exception e) {
                            logger.error("Failed to get quota info for service {} in region {}.", serviceCode,
                                    region.getRegionId(), e);
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
            Optional<DashboardData.IamResources> cachedData = redisCache.get(cacheKey,
                    DashboardData.IamResources.class);
            if (cachedData.isPresent()) {
                return CompletableFuture.completedFuture(cachedData.get());
            }
        }

        IamClient iam = awsClientProvider.getIamClient(account);
        logger.info("Fetching IAM resources for account {}...", account.getAwsAccountId());
        int users = 0, groups = 0, policies = 0, roles = 0;
        try {
            users = iam.listUsers().users().size();
        } catch (Exception e) {
            logger.error("IAM check failed for Users on account {}", account.getAwsAccountId(), e);
        }
        try {
            groups = iam.listGroups().groups().size();
        } catch (Exception e) {
            logger.error("IAM check failed for Groups on account {}", account.getAwsAccountId(), e);
        }
        try {
            policies = iam.listPolicies(r -> r.scope(PolicyScopeType.LOCAL)).policies().size();
        } catch (Exception e) {
            logger.error("IAM check failed for Policies on account {}", account.getAwsAccountId(), e);
        }
        try {
            roles = iam.listRoles().roles().size();
        } catch (Exception e) {
            logger.error("IAM check failed for Roles on account {}", account.getAwsAccountId(), e);
        }

        DashboardData.IamResources resources = new DashboardData.IamResources(users, groups, policies, roles);
        redisCache.put(cacheKey, resources, 10);
        return CompletableFuture.completedFuture(resources);
    }

    // --- NEW METHOD TO FETCH DETAILED IAM USERS & ROLES ---
    @Async("awsTaskExecutor")
    public CompletableFuture<DashboardData.IamDetail> getIamDetails(CloudAccount account, boolean forceRefresh) {
        String cacheKey = "iamDetails-" + account.getAwsAccountId();
        if (!forceRefresh) {
            Optional<DashboardData.IamDetail> cachedData = redisCache.get(cacheKey, DashboardData.IamDetail.class);
            if (cachedData.isPresent()) {
                return CompletableFuture.completedFuture(cachedData.get());
            }
        }

        return CompletableFuture.supplyAsync(() -> {
            IamClient iam = awsClientProvider.getIamClient(account);

            // Parallel fetch for users
            List<DashboardData.IamUserDetail> users = iam.listUsers().users().parallelStream().map(user -> {
                try {
                    List<String> groups = iam.listGroupsForUser(r -> r.userName(user.userName()))
                            .groups().stream().map(g -> g.groupName()).collect(Collectors.toList());

                    List<DashboardData.IamPolicyDetail> policies = new ArrayList<>();

                    iam.listAttachedUserPolicies(r -> r.userName(user.userName())).attachedPolicies()
                            .forEach(p -> policies
                                    .add(new DashboardData.IamPolicyDetail(p.policyName(), p.policyArn(), "Managed")));

                    iam.listUserPolicies(r -> r.userName(user.userName())).policyNames()
                            .forEach(p -> policies.add(new DashboardData.IamPolicyDetail(p, null, "Inline")));

                    return new DashboardData.IamUserDetail(
                            user.userName(), user.userId(), user.arn(),
                            user.createDate().toString(),
                            user.passwordLastUsed() != null ? user.passwordLastUsed().toString() : "Never",
                            groups, policies);
                } catch (Exception e) {
                    logger.warn("Failed to fetch details for user {}", user.userName());
                    return null;
                }
            }).filter(java.util.Objects::nonNull).collect(Collectors.toList());

            // Parallel fetch for roles
            List<DashboardData.IamRoleDetail> roles = iam.listRoles().roles().parallelStream()
                    .filter(r -> !r.path().startsWith("/aws-service-role/"))
                    .map(role -> {
                        try {
                            List<DashboardData.IamPolicyDetail> policies = new ArrayList<>();
                            iam.listAttachedRolePolicies(r -> r.roleName(role.roleName())).attachedPolicies()
                                    .forEach(p -> policies.add(new DashboardData.IamPolicyDetail(p.policyName(),
                                            p.policyArn(), "Managed")));

                            iam.listRolePolicies(r -> r.roleName(role.roleName())).policyNames()
                                    .forEach(p -> policies.add(new DashboardData.IamPolicyDetail(p, null, "Inline")));

                            return new DashboardData.IamRoleDetail(
                                    role.roleName(), role.roleId(), role.arn(),
                                    role.createDate().toString(), role.description(), policies);
                        } catch (Exception e) {
                            logger.warn("Failed to fetch details for role {}", role.roleName());
                            return null;
                        }
                    }).filter(java.util.Objects::nonNull).collect(Collectors.toList());

            DashboardData.IamDetail details = new DashboardData.IamDetail(users, roles);
            redisCache.put(cacheKey, details, 10);
            return details;
        });
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

                    double rightsizingSavings = Stream
                            .of(ec2RecsFuture.join(), ebsRecsFuture.join(), lambdaRecsFuture.join())
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
            List<DashboardData.CostAnomaly> anomalies) {
        double rightsizingSavings = Stream.of(ec2Recs, ebsRecs, lambdaRecs)
                .flatMap(List::stream)
                .mapToDouble(DashboardData.OptimizationRecommendation::getEstimatedMonthlySavings)
                .sum();

        double wasteSavings = wastedResources.stream()
                .mapToDouble(DashboardData.WastedResource::getMonthlySavings)
                .sum();

        double totalSavings = rightsizingSavings + wasteSavings;
        long criticalAlerts = (anomalies != null ? anomalies.size() : 0) + ec2Recs.size() + ebsRecs.size()
                + lambdaRecs.size();
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