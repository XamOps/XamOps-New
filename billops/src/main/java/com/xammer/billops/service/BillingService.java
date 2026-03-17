package com.xammer.billops.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.billops.domain.CloudAccount;
import com.xammer.billops.dto.BillingDashboardDto;
import com.xammer.billops.dto.RegionCostDto;
import com.xammer.billops.dto.ResourceCostDto;
import com.xammer.billops.dto.ServiceCostDetailDto;
import com.xammer.billops.repository.BillOpsCachedDataRepository;
import com.xammer.billops.repository.CloudAccountRepository;
import com.xammer.cloud.domain.CachedData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class BillingService {

    private static final Logger logger = LoggerFactory.getLogger(BillingService.class);
    private final CostService costService;
    private final GcpCostService gcpCostService;
    private final AzureCostService azureCostService;
    private final CloudAccountRepository cloudAccountRepository;
    private final RedisCacheService redisCache;
    private final CacheManager cacheManager;

    private static final List<String> COST_SPRING_CACHES =
            List.of("costByDimension", "costForServiceInRegion", "costByResource", "costHistory");

    // Redis cache prefixes
    private static final String BILLING_DASHBOARD_CACHE_PREFIX = "billing:dashboard:";
    private static final String DETAILED_BILLING_REPORT_CACHE_PREFIX = "billing:detailed:";
    private static final long CACHE_TTL_MINUTES = 60;

    // DB snapshot key prefixes
    private static final String BILLING_DASHBOARD_DB_PREFIX = "BILLOPS_BILLING_DASHBOARD::";
    private static final String BILLING_DETAILED_DB_PREFIX = "BILLOPS_BILLING_DETAILED::";

    private final BillOpsCachedDataRepository cachedDataRepository;
    private final ObjectMapper objectMapper;

    public BillingService(CostService costService,
            GcpCostService gcpCostService,
            AzureCostService azureCostService,
            CloudAccountRepository cloudAccountRepository,
            RedisCacheService redisCache,
            BillOpsCachedDataRepository cachedDataRepository,
            ObjectMapper objectMapper,
            CacheManager cacheManager) {
        this.costService = costService;
        this.gcpCostService = gcpCostService;
        this.azureCostService = azureCostService;
        this.cloudAccountRepository = cloudAccountRepository;
        this.redisCache = redisCache;
        this.cachedDataRepository = cachedDataRepository;
        this.objectMapper = objectMapper;
        this.cacheManager = cacheManager;
    }

    /**
     * Clears all Spring @Cacheable cost caches (costByDimension, costForServiceInRegion,
     * costByResource, costHistory) so the next API call fetches live data from AWS.
     * Called when forceRefresh=true so billing page refresh and invoice generation
     * always read from the same fresh data source.
     */
    private void evictSpringCostCaches() {
        COST_SPRING_CACHES.forEach(cacheName -> {
            org.springframework.cache.Cache cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                cache.clear();
                logger.info("[BillingService] Evicted Spring cache: {}", cacheName);
            }
        });
    }

    @PostConstruct
    public void init() {
        logger.info("✅ BillingService initialized");
    }

    /**
     * High-performance method to get Billing Summary.
     * 3-layer cache: Redis (60 min) → DB snapshot → Cloud APIs.
     */
    public BillingDashboardDto getBillingData(List<String> accountIds, Integer year, Integer month,
            boolean forceRefresh) {
        String cacheKey = generateCacheKey(BILLING_DASHBOARD_CACHE_PREFIX, accountIds, year, month);
        String dbKey = BILLING_DASHBOARD_DB_PREFIX + generateKeySuffix(accountIds, year, month);

        if (!forceRefresh) {
            // Layer 1: Redis
            Optional<BillingDashboardDto> cached = redisCache.get(cacheKey, BillingDashboardDto.class);
            if (cached.isPresent()) {
                logger.debug("Returning Redis-cached billing dashboard for {}", cacheKey);
                return cached.get();
            }
            // Layer 2: DB snapshot
            Optional<CachedData> dbEntry = cachedDataRepository.findById(dbKey);
            if (dbEntry.isPresent()) {
                try {
                    BillingDashboardDto dbData = objectMapper.readValue(
                            dbEntry.get().getJsonData(), BillingDashboardDto.class);
                    redisCache.put(cacheKey, dbData, CACHE_TTL_MINUTES);
                    logger.info("Returning DB-cached billing dashboard for {}, re-warmed Redis", dbKey);
                    return dbData;
                } catch (Exception e) {
                    logger.warn("Failed to deserialize DB billing snapshot for {}: {}", dbKey, e.getMessage());
                }
            }
        } else {
            // forceRefresh=true: also clear Spring @Cacheable cost caches so getCostByDimension
            // fetches live data from AWS (not a stale Spring-cached value).
            evictSpringCostCaches();
        }

        // Layer 3: Fetch fresh from cloud APIs (parallel)
        logger.info("Fetching FRESH summary billing data for accounts: {}", accountIds);
        List<CloudAccount> accounts = getUniqueAccounts(accountIds);

        if (accounts.isEmpty()) {
            return new BillingDashboardDto();
        }

        List<CompletableFuture<BillingDashboardDto>> futures = accounts.stream()
                .map(account -> CompletableFuture.supplyAsync(() -> getAccountBillingSummary(account, year, month)))
                .collect(Collectors.toList());

        BillingDashboardDto aggregatedData = futures.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .reduce(new BillingDashboardDto(), this::mergeBillingDashboards);

        aggregatedData.setLastUpdated(System.currentTimeMillis());
        // Save to Redis + DB
        redisCache.put(cacheKey, aggregatedData, CACHE_TTL_MINUTES);
        saveDashboardToDb(dbKey, aggregatedData);

        return aggregatedData;
    }

    /**
     * High-performance method to get Detailed Breakdown.
     * 3-layer cache: Redis (60 min) → DB snapshot → Cloud APIs.
     */
    public List<ServiceCostDetailDto> getDetailedBillingReport(List<String> accountIds, Integer year, Integer month,
            boolean forceRefresh) {
        String cacheKey = generateCacheKey(DETAILED_BILLING_REPORT_CACHE_PREFIX, accountIds, year, month);
        String dbKey = BILLING_DETAILED_DB_PREFIX + generateKeySuffix(accountIds, year, month);

        if (!forceRefresh) {
            // Layer 1: Redis
            Optional<List<ServiceCostDetailDto>> cached = redisCache.get(cacheKey,
                    new TypeReference<List<ServiceCostDetailDto>>() {});
            if (cached.isPresent()) {
                logger.debug("Returning Redis-cached billing detailed for {}", cacheKey);
                return cached.get();
            }
            // Layer 2: DB snapshot
            Optional<CachedData> dbEntry = cachedDataRepository.findById(dbKey);
            if (dbEntry.isPresent()) {
                try {
                    List<ServiceCostDetailDto> dbData = objectMapper.readValue(
                            dbEntry.get().getJsonData(),
                            new TypeReference<List<ServiceCostDetailDto>>() {});
                    redisCache.put(cacheKey, dbData, CACHE_TTL_MINUTES);
                    logger.info("Returning DB-cached billing detailed for {}, re-warmed Redis", dbKey);
                    return dbData;
                } catch (Exception e) {
                    logger.warn("Failed to deserialize DB billing detailed snapshot for {}: {}", dbKey, e.getMessage());
                }
            }
        } else {
            // forceRefresh=true: clear Spring @Cacheable cost caches so all cost methods
            // fetch live data from AWS instead of returning stale Spring-cached values.
            evictSpringCostCaches();
        }

        // Layer 3: Fetch fresh from cloud APIs (parallel)
        logger.info("Fetching FRESH detailed billing report for accounts: {}", accountIds);
        List<CloudAccount> accounts = getUniqueAccounts(accountIds);

        if (accounts.isEmpty()) {
            return Collections.emptyList();
        }

        List<CompletableFuture<List<ServiceCostDetailDto>>> futures = accounts.stream()
                .map(account -> CompletableFuture.supplyAsync(() -> fetchSingleAccountDetails(account, year, month)))
                .collect(Collectors.toList());

        List<ServiceCostDetailDto> aggregatedReport = futures.stream()
                .map(CompletableFuture::join)
                .flatMap(List::stream)
                .collect(Collectors.groupingBy(ServiceCostDetailDto::getServiceName))
                .values().stream()
                .map(this::mergeServiceDetails)
                .sorted(Comparator.comparing(ServiceCostDetailDto::getTotalCost).reversed())
                .collect(Collectors.toList());

        // Save to Redis + DB
        redisCache.put(cacheKey, aggregatedReport, CACHE_TTL_MINUTES);
        saveDetailedToDb(dbKey, aggregatedReport);

        return aggregatedReport;
    }

    // --- Private Helper Methods ---

    public BillingDashboardDto getAccountBillingSummary(CloudAccount account, Integer year, Integer month) {
        try {
            String provider = account.getProvider();
            if ("GCP".equalsIgnoreCase(provider)) {
                return fetchGcpBillingSummary(account, year, month);
            } else if ("Azure".equalsIgnoreCase(provider)) {
                return fetchAzureBillingSummary(account, year, month);
            } else {
                // Default: AWS
                return fetchAwsBillingSummary(account, year, month);
            }
        } catch (Exception e) {
            logger.error("Error fetching summary for account {}: {}",
                    account.getAwsAccountId() != null ? account.getAwsAccountId() : account.getAccountName(),
                    e.getMessage());
            return new BillingDashboardDto();
        }
    }

    private BillingDashboardDto fetchAwsBillingSummary(CloudAccount account, Integer year, Integer month) {
        BillingDashboardDto dto = new BillingDashboardDto();

        // Fetch Cost History
        List<Map<String, Object>> costHistoryRaw = costService.getCostHistory(account, year, month);
        List<BillingDashboardDto.CostHistory> costHistory = costHistoryRaw.stream()
                .map(item -> new BillingDashboardDto.CostHistory(
                        (String) item.get("date"),
                        ((Number) Optional.ofNullable(item.get("cost")).orElse(0.0)).doubleValue()))
                .collect(Collectors.toList());
        dto.setCostHistory(costHistory);

        // Fetch Service Breakdown
        List<Map<String, Object>> serviceBreakdownRaw = costService.getCostByDimension(account, "SERVICE", year,
                month);
        List<BillingDashboardDto.ServiceBreakdown> serviceBreakdown = serviceBreakdownRaw.stream()
                .map(item -> new BillingDashboardDto.ServiceBreakdown(
                        (String) item.get("name"),
                        ((Number) Optional.ofNullable(item.get("cost")).orElse(0.0)).doubleValue()))
                .collect(Collectors.toList());
        dto.setServiceBreakdown(serviceBreakdown);

        return dto;
    }

    /**
     * Converts an amount from the account's native billing currency to USD.
     * Returns the original amount unchanged if the account is already in USD
     * (billingCurrency is null/blank/"USD") or if no valid exchange rate is set.
     * Exchange rate semantics: how many native-currency units = 1 USD.
     * e.g. billingCurrency="INR", usdExchangeRate=84.0 → amount / 84.0
     */
    private double convertToUsd(double amount, CloudAccount account) {
        String currency = account.getBillingCurrency();
        if (currency == null || currency.isBlank() || "USD".equalsIgnoreCase(currency)) {
            return amount;
        }
        Double rate = account.getUsdExchangeRate();
        if (rate == null || rate <= 0 || rate == 1.0) {
            logger.warn("No valid usdExchangeRate for account {} (currency={}). Returning raw amount.",
                    account.getAccountName(), currency);
            return amount;
        }
        return amount / rate;
    }

    private BillingDashboardDto fetchGcpBillingSummary(CloudAccount account, Integer year, Integer month) {
        try {
            String accountIdentifier = account.getGcpProjectId();
            logger.info("🔍 Fetching GCP billing summary for account: {} | Project ID: {}", account.getAccountName(),
                    accountIdentifier);

            if (accountIdentifier == null || accountIdentifier.isBlank()) {
                logger.warn("⚠️ GCP Project ID is missing for account: {}", account.getAccountName());
                return new BillingDashboardDto();
            }

            Optional<com.xammer.billops.dto.GcpBillingDashboardDto> gcpDtoOpt = gcpCostService
                    .getGcpBillingDashboardDtoAndCache(accountIdentifier);

            if (gcpDtoOpt.isPresent()) {
                com.xammer.billops.dto.GcpBillingDashboardDto gcpDto = gcpDtoOpt.get();
                logger.info("✅ Successfully fetched GCP data for {}. MTD Cost: {}", accountIdentifier,
                        gcpDto.getCostThisMonth());

                BillingDashboardDto dto = new BillingDashboardDto();

                // Map History (with currency conversion to USD)
                if (gcpDto.getCostHistory() != null) {
                    dto.setCostHistory(gcpDto.getCostHistory().stream()
                            .map(h -> new BillingDashboardDto.CostHistory(h.getDate(),
                                    convertToUsd(h.getAmount().doubleValue(), account)))
                            .collect(Collectors.toList()));
                }

                // Map Service Breakdown (with currency conversion to USD)
                if (gcpDto.getServiceBreakdown() != null) {
                    dto.setServiceBreakdown(gcpDto.getServiceBreakdown().stream()
                            .map(s -> new BillingDashboardDto.ServiceBreakdown(s.getServiceName(),
                                    convertToUsd(s.getAmount().doubleValue(), account)))
                            .collect(Collectors.toList()));
                }

                return dto;
            } else {
                logger.warn("⚠️ GcpCostService returned empty Optional for project: {}", accountIdentifier);
            }
        } catch (Exception e) {
            logger.error("❌ Error fetching GCP summary for account {}: {}", account.getGcpProjectId(), e.getMessage(),
                    e);
        }
        return new BillingDashboardDto();
    }

    private BillingDashboardDto fetchAzureBillingSummary(CloudAccount account, Integer year, Integer month) {
        try {
            String subscriptionId = account.getAzureSubscriptionId();
            logger.info("🔍 Fetching Azure billing summary for account: {} | Subscription: {}",
                    account.getAccountName(), subscriptionId);

            if (subscriptionId == null || subscriptionId.isBlank()) {
                logger.warn("⚠️ Azure Subscription ID is missing for account: {}", account.getAccountName());
                return new BillingDashboardDto();
            }

            com.xammer.billops.dto.azure.AzureBillingDashboardDto azureDto = azureCostService
                    .getBillingDashboard(subscriptionId, year, month);

            boolean hasBillopsData = azureDto != null
                    && ((azureDto.getServiceBreakdown() != null && !azureDto.getServiceBreakdown().isEmpty())
                            || (azureDto.getCostHistory() != null && !azureDto.getCostHistory().isEmpty()));

            if (hasBillopsData) {
                logger.info("✅ Successfully fetched Azure data for {}. Total Cost: {}", subscriptionId,
                        azureDto.getTotalCost());
                BillingDashboardDto dto = new BillingDashboardDto();

                // Map History (with currency conversion to USD)
                if (azureDto.getCostHistory() != null) {
                    dto.setCostHistory(azureDto.getCostHistory().stream()
                            .map(h -> new BillingDashboardDto.CostHistory(h.getDate(),
                                    convertToUsd(h.getCost(), account)))
                            .collect(Collectors.toList()));
                }

                // Map Service Breakdown (with currency conversion to USD)
                if (azureDto.getServiceBreakdown() != null) {
                    dto.setServiceBreakdown(azureDto.getServiceBreakdown().stream()
                            .map(s -> new BillingDashboardDto.ServiceBreakdown(s.getServiceName(),
                                    convertToUsd(s.getAmount(), account)))
                            .collect(Collectors.toList()));
                }

                return dto;
            }

            // Fallback: read from xamops-service cache (azure:billing-summary: / azure:cost-history:)
            logger.info("⚠️ Billops Azure cache miss for {}. Trying xamops-service cache fallback.", subscriptionId);
            return buildDtoFromXamopsAzureCache(subscriptionId, account);

        } catch (Exception e) {
            logger.error("❌ Error fetching Azure summary for account {}: {}", account.getAzureSubscriptionId(),
                    e.getMessage(), e);
        }
        return new BillingDashboardDto();
    }

    /**
     * Fallback: reads Azure billing data cached by the xamops-service ingestion job.
     * Keys: "azure:billing-summary:{subscriptionId}" and "azure:cost-history:{subscriptionId}"
     */
    @SuppressWarnings("unchecked")
    private BillingDashboardDto buildDtoFromXamopsAzureCache(String subscriptionId, CloudAccount account) {
        BillingDashboardDto dto = new BillingDashboardDto();

        // Service breakdown: List of {serviceName, monthToDateCost}
        Optional<List<Map<String, Object>>> summaryOpt = redisCache.get(
                "azure:billing-summary:" + subscriptionId,
                new TypeReference<List<Map<String, Object>>>() {});
        if (summaryOpt.isPresent() && !summaryOpt.get().isEmpty()) {
            List<BillingDashboardDto.ServiceBreakdown> breakdown = summaryOpt.get().stream()
                    .map(entry -> {
                        String name = (String) entry.getOrDefault("serviceName", "Unknown");
                        double cost = entry.get("monthToDateCost") instanceof Number
                                ? ((Number) entry.get("monthToDateCost")).doubleValue() : 0.0;
                        return new BillingDashboardDto.ServiceBreakdown(name, convertToUsd(cost, account));
                    })
                    .collect(Collectors.toList());
            dto.setServiceBreakdown(breakdown);
            logger.info("✅ Azure fallback: loaded {} services from xamops cache for {}", breakdown.size(), subscriptionId);
        }

        // Cost history: {labels: [...], costs: [...], anomalies: [...]}
        Optional<Map<String, Object>> historyOpt = redisCache.get(
                "azure:cost-history:" + subscriptionId,
                new TypeReference<Map<String, Object>>() {});
        if (historyOpt.isPresent()) {
            Map<String, Object> historyData = historyOpt.get();
            List<String> labels = (List<String>) historyData.get("labels");
            List<?> rawCosts = (List<?>) historyData.get("costs");
            if (labels != null && rawCosts != null) {
                List<BillingDashboardDto.CostHistory> costHistory = new ArrayList<>();
                for (int i = 0; i < Math.min(labels.size(), rawCosts.size()); i++) {
                    double cost = rawCosts.get(i) instanceof Number
                            ? ((Number) rawCosts.get(i)).doubleValue() : 0.0;
                    costHistory.add(new BillingDashboardDto.CostHistory(
                            labels.get(i), convertToUsd(cost, account)));
                }
                dto.setCostHistory(costHistory);
                logger.info("✅ Azure fallback: loaded {} history entries from xamops cache for {}", costHistory.size(), subscriptionId);
            }
        }

        return dto;
    }

    private List<ServiceCostDetailDto> fetchSingleAccountDetails(CloudAccount account, Integer year, Integer month) {
        List<ServiceCostDetailDto> accountDetails = new ArrayList<>();
        try {
            List<Map<String, Object>> services = costService.getCostByDimension(account, "SERVICE", year, month);

            for (Map<String, Object> serviceMap : services) {
                String serviceName = (String) serviceMap.get("name");
                double totalServiceCost = ((Number) Optional.ofNullable(serviceMap.get("cost")).orElse(0.0))
                        .doubleValue();

                if (serviceName == null || totalServiceCost <= 0)
                    continue;

                List<RegionCostDto> regionsDto = new ArrayList<>();
                List<Map<String, Object>> regions = costService.getCostForServiceInRegion(account, serviceName, year,
                        month);

                if (regions.isEmpty()) {
                    // Assume Global or fetch without region constraint
                    List<Map<String, Object>> resources = costService.getCostByResource(account, serviceName, "Global",
                            year, month);
                    List<ResourceCostDto> resourceCosts = mapResourcesToDto(resources);
                    if (!resourceCosts.isEmpty()) {
                        regionsDto.add(new RegionCostDto("Global", totalServiceCost, resourceCosts));
                    }
                } else {
                    for (Map<String, Object> regionMap : regions) {
                        String regionName = (String) regionMap.get("name");
                        double regionCost = ((Number) Optional.ofNullable(regionMap.get("cost")).orElse(0.0))
                                .doubleValue();
                        if (regionName == null || regionCost <= 0)
                            continue;

                        List<Map<String, Object>> resources = costService.getCostByResource(account, serviceName,
                                regionName, year, month);
                        regionsDto.add(new RegionCostDto(regionName, regionCost, mapResourcesToDto(resources)));
                    }
                }

                if (!regionsDto.isEmpty()) {
                    accountDetails.add(new ServiceCostDetailDto(serviceName, totalServiceCost, regionsDto));
                }
            }
        } catch (Exception e) {
            logger.error("Error fetching details for account {}: {}", account.getAwsAccountId(), e.getMessage());
        }
        return accountDetails;
    }

    private BillingDashboardDto mergeBillingDashboards(BillingDashboardDto d1, BillingDashboardDto d2) {
        BillingDashboardDto merged = new BillingDashboardDto();

        // Merge History
        Map<String, Double> historyMap = new HashMap<>();
        if (d1.getCostHistory() != null)
            d1.getCostHistory().forEach(h -> historyMap.merge(h.getDate(), h.getCost(), Double::sum));
        if (d2.getCostHistory() != null)
            d2.getCostHistory().forEach(h -> historyMap.merge(h.getDate(), h.getCost(), Double::sum));

        merged.setCostHistory(historyMap.entrySet().stream()
                .map(e -> new BillingDashboardDto.CostHistory(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(BillingDashboardDto.CostHistory::getDate))
                .collect(Collectors.toList()));

        // Merge Services
        Map<String, Double> serviceMap = new HashMap<>();
        if (d1.getServiceBreakdown() != null)
            d1.getServiceBreakdown().forEach(s -> serviceMap.merge(s.getName(), s.getCost(), Double::sum));
        if (d2.getServiceBreakdown() != null)
            d2.getServiceBreakdown().forEach(s -> serviceMap.merge(s.getName(), s.getCost(), Double::sum));

        merged.setServiceBreakdown(serviceMap.entrySet().stream()
                .map(e -> new BillingDashboardDto.ServiceBreakdown(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(BillingDashboardDto.ServiceBreakdown::getCost).reversed())
                .collect(Collectors.toList()));

        return merged;
    }

    private ServiceCostDetailDto mergeServiceDetails(List<ServiceCostDetailDto> dtos) {
        if (dtos.isEmpty())
            return null;
        ServiceCostDetailDto first = dtos.get(0);
        if (dtos.size() == 1)
            return first;

        double totalCost = dtos.stream().mapToDouble(ServiceCostDetailDto::getTotalCost).sum();

        List<RegionCostDto> allRegions = dtos.stream()
                .flatMap(d -> d.getRegionCosts().stream())
                .collect(Collectors.toList());

        return new ServiceCostDetailDto(first.getServiceName(), totalCost, allRegions);
    }

    private List<ResourceCostDto> mapResourcesToDto(List<Map<String, Object>> resources) {
        if (resources == null)
            return Collections.emptyList();
        return resources.stream()
                .map(resourceMap -> {
                    String rawUsageType = (String) resourceMap.get("name");
                    String resourceId = (String) resourceMap.get("id");
                    if (rawUsageType == null)
                        return null;

                    double cost = ((Number) Optional.ofNullable(resourceMap.get("cost")).orElse(0.0)).doubleValue();
                    if (cost <= 0)
                        return null;

                    double quantity = ((Number) Optional.ofNullable(resourceMap.get("quantity")).orElse(0.0))
                            .doubleValue();
                    String unit = (String) Optional.ofNullable(resourceMap.get("unit")).orElse("");

                    return new ResourceCostDto(
                            resourceId != null ? resourceId : rawUsageType,
                            formatUsageType(rawUsageType),
                            quantity, cost, unit);
                })
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(ResourceCostDto::getCost).reversed())
                .collect(Collectors.toList());
    }

    private List<CloudAccount> getUniqueAccounts(List<String> accountIds) {
        List<CloudAccount> allAccounts = cloudAccountRepository.findByAnyCloudIdIn(accountIds);
        Map<Long, CloudAccount> uniqueAccountsMap = allAccounts.stream()
                .collect(Collectors.toMap(
                        CloudAccount::getId,
                        Function.identity(),
                        (existing, replacement) -> existing));
        return new ArrayList<>(uniqueAccountsMap.values());
    }

    private String generateCacheKey(String prefix, List<String> accountIds, Integer year, Integer month) {
        List<String> sortedIds = new ArrayList<>(accountIds);
        Collections.sort(sortedIds);
        return prefix + String.join(",", sortedIds) + ":" + year + ":" + month;
    }

    // formatUsageType remains the same
    private String formatUsageType(String usageType) {
        if (usageType == null)
            return "Unknown Usage";
        if (usageType.matches("^[A-Z]{2,4}\\d-.*"))
            usageType = usageType.substring(usageType.indexOf('-') + 1);

        if (usageType.startsWith("BoxUsage:") || usageType.startsWith("InstanceUsage:"))
            return "EC2 On Demand Linux " + usageType.substring(usageType.indexOf(':') + 1) + " Instance Hour";

        if (usageType.startsWith("EBS:"))
            usageType = usageType.substring(4);
        if (usageType.equalsIgnoreCase("VolumeUsage.gp3"))
            return "EBS General Purpose SSD (gp3) Volume Storage";
        if (usageType.equalsIgnoreCase("VolumeUsage.gp2"))
            return "EBS General Purpose SSD (gp2) Volume Storage";
        if (usageType.equalsIgnoreCase("VolumeUsage.io1"))
            return "EBS Provisioned IOPS SSD (io1) Volume Storage";
        if (usageType.equalsIgnoreCase("VolumeUsage.io2"))
            return "EBS Provisioned IOPS SSD (io2) Volume Storage";
        if (usageType.equalsIgnoreCase("VolumeUsage.st1"))
            return "EBS Throughput Optimized HDD (st1) Volume Storage";
        if (usageType.equalsIgnoreCase("VolumeUsage.sc1"))
            return "EBS Cold HDD (sc1) Volume Storage";
        if (usageType.equalsIgnoreCase("VolumeP-IOPS.io1"))
            return "EBS Provisioned IOPS SSD (io1) IOPS";
        if (usageType.equalsIgnoreCase("VolumeP-IOPS.io2"))
            return "EBS Provisioned IOPS SSD (io2) IOPS";

        if (usageType.contains("DataTransfer")) {
            if (usageType.contains("Regional-Bytes"))
                return "Data Transfer Regional";
            if (usageType.contains("Out-Bytes"))
                return "Data Transfer Out to Internet";
            if (usageType.contains("In-Bytes"))
                return "Data Transfer In from Internet";
            return "Data Transfer (Other)";
        }

        if (usageType.startsWith("NatGateway")) {
            if (usageType.endsWith("-Bytes"))
                return "NAT Gateway Data Processed";
            if (usageType.endsWith("-Hours"))
                return "NAT Gateway Hourly Charge";
        }

        if (usageType.equalsIgnoreCase("LoadBalancerUsage"))
            return "ELB Application Load Balancer Hours";
        if (usageType.equalsIgnoreCase("LCUUsage"))
            return "ELB Application Load Balancer Capacity Units (LCU Hours)";
        if (usageType.contains("NetworkLoadBalancer"))
            return "ELB Network Load Balancer";

        if (usageType.startsWith("TimedStorage-")) {
            if (usageType.contains("StandardIA"))
                return "S3 Storage Standard - Infrequent Access";
            if (usageType.contains("OneZoneIA"))
                return "S3 Storage One Zone - Infrequent Access";
            if (usageType.contains("Glacier"))
                return "S3 Storage Glacier";
            if (usageType.contains("Intelligent"))
                return "S3 Storage Intelligent Tiering";
            return "S3 Storage Standard";
        }
        if (usageType.startsWith("Requests-"))
            return "S3 API Requests (" + usageType.substring(9) + ")";
        if (usageType.equalsIgnoreCase("TimedStorage-ByteHrs"))
            return "ECR data storage";

        if (usageType.contains("InstanceUsage"))
            return "RDS Instance Usage";
        if (usageType.contains("StorageUsage"))
            return "RDS Storage Usage";
        if (usageType.contains("PIOPS"))
            return "RDS Provisioned IOPS";

        if (usageType.equalsIgnoreCase("APIRequest"))
            return "API Gateway Requests";
        if (usageType.equalsIgnoreCase("PaidComplianceCheck"))
            return "Security Hub Compliance Check";
        if (usageType.contains("Config"))
            return "AWS Config Item Recorded";

        return usageType.replace('-', ' ').replaceAll("(\\p{Ll})(\\p{Lu})", "$1 $2");
    }

    // ── DB snapshot helpers ──────────────────────────────────────────────────────

    private void saveDashboardToDb(String dbKey, BillingDashboardDto data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            CachedData entity = new CachedData();
            entity.setCacheKey(dbKey);
            entity.setJsonData(json);
            entity.setLastUpdated(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
            cachedDataRepository.save(entity);
            logger.debug("Saved billing dashboard snapshot to DB: {}", dbKey);
        } catch (Exception e) {
            logger.warn("Failed to save billing dashboard snapshot to DB {}: {}", dbKey, e.getMessage());
        }
    }

    private void saveDetailedToDb(String dbKey, List<ServiceCostDetailDto> data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            CachedData entity = new CachedData();
            entity.setCacheKey(dbKey);
            entity.setJsonData(json);
            entity.setLastUpdated(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
            cachedDataRepository.save(entity);
            logger.debug("Saved billing detailed snapshot to DB: {}", dbKey);
        } catch (Exception e) {
            logger.warn("Failed to save billing detailed snapshot to DB {}: {}", dbKey, e.getMessage());
        }
    }

    /** Generates the sortable suffix used for both Redis and DB keys */
    private String generateKeySuffix(List<String> accountIds, Integer year, Integer month) {
        List<String> sorted = new ArrayList<>(accountIds);
        Collections.sort(sorted);
        return String.join(",", sorted) + ":" + year + ":" + month;
    }

    public void evictBillingCaches() {
        logger.info(
                "Cache eviction triggered via TTL or manual key deletion logic if implemented in RedisCacheService.");
    }
}