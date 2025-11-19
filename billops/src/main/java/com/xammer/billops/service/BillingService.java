package com.xammer.billops.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.xammer.billops.domain.CloudAccount;
import com.xammer.billops.dto.BillingDashboardDto;
import com.xammer.billops.dto.RegionCostDto;
import com.xammer.billops.dto.ResourceCostDto;
import com.xammer.billops.dto.ServiceCostDetailDto;
import com.xammer.billops.repository.CloudAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class BillingService {

    private static final Logger logger = LoggerFactory.getLogger(BillingService.class);
    private final CostService costService;
    private final CloudAccountRepository cloudAccountRepository;
    private final RedisCacheService redisCache;

    // Define cache names/prefixes as constants
    private static final String BILLING_DASHBOARD_CACHE_PREFIX = "billing:dashboard:";
    private static final String DETAILED_BILLING_REPORT_CACHE_PREFIX = "billing:detailed:";
    private static final long CACHE_TTL_MINUTES = 60;

    public BillingService(CostService costService,
                          CloudAccountRepository cloudAccountRepository,
                          RedisCacheService redisCache) {
        this.costService = costService;
        this.cloudAccountRepository = cloudAccountRepository;
        this.redisCache = redisCache;
    }

    /**
     * High-performance method to get Billing Summary.
     * 1. Checks Redis Cache (fast return).
     * 2. If missing or forced: Fetches from AWS in parallel.
     * 3. Aggregates and Caches the result.
     */
    public BillingDashboardDto getBillingData(List<String> accountIds, Integer year, Integer month, boolean forceRefresh) {
        String cacheKey = generateCacheKey(BILLING_DASHBOARD_CACHE_PREFIX, accountIds, year, month);

        // 1. Try loading from Cache
        if (!forceRefresh) {
            Optional<BillingDashboardDto> cached = redisCache.get(cacheKey, BillingDashboardDto.class);
            if (cached.isPresent()) {
                return cached.get();
            }
        }

        // 2. Fetch Fresh Data (Parallel Execution)
        logger.info("Fetching FRESH summary billing data for accounts: {}", accountIds);
        List<CloudAccount> accounts = getUniqueAccounts(accountIds);

        if (accounts.isEmpty()) {
            return new BillingDashboardDto();
        }

        List<CompletableFuture<BillingDashboardDto>> futures = accounts.stream()
                .map(account -> CompletableFuture.supplyAsync(() -> fetchSingleAccountSummary(account, year, month)))
                .collect(Collectors.toList());

        // 3. Aggregate Results
        BillingDashboardDto aggregatedData = futures.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .reduce(new BillingDashboardDto(), this::mergeBillingDashboards);

        // 4. Update Cache
        redisCache.put(cacheKey, aggregatedData, CACHE_TTL_MINUTES);

        return aggregatedData;
    }

    /**
     * High-performance method to get Detailed Breakdown.
     * Optimized with parallel account processing.
     */
    public List<ServiceCostDetailDto> getDetailedBillingReport(List<String> accountIds, Integer year, Integer month, boolean forceRefresh) {
        String cacheKey = generateCacheKey(DETAILED_BILLING_REPORT_CACHE_PREFIX, accountIds, year, month);

        // 1. Try loading from Cache
        if (!forceRefresh) {
            Optional<List<ServiceCostDetailDto>> cached = redisCache.get(cacheKey, new TypeReference<List<ServiceCostDetailDto>>() {});
            if (cached.isPresent()) {
                return cached.get();
            }
        }

        // 2. Fetch Fresh Data (Parallel Execution)
        logger.info("Fetching FRESH detailed billing report for accounts: {}", accountIds);
        List<CloudAccount> accounts = getUniqueAccounts(accountIds);

        if (accounts.isEmpty()) {
            return Collections.emptyList();
        }

        List<CompletableFuture<List<ServiceCostDetailDto>>> futures = accounts.stream()
                .map(account -> CompletableFuture.supplyAsync(() -> fetchSingleAccountDetails(account, year, month)))
                .collect(Collectors.toList());

        // 3. Aggregate Results
        List<ServiceCostDetailDto> aggregatedReport = futures.stream()
                .map(CompletableFuture::join)
                .flatMap(List::stream)
                // Merge same services across accounts
                .collect(Collectors.groupingBy(ServiceCostDetailDto::getServiceName))
                .values().stream()
                .map(this::mergeServiceDetails)
                .sorted(Comparator.comparing(ServiceCostDetailDto::getTotalCost).reversed())
                .collect(Collectors.toList());

        // 4. Update Cache
        redisCache.put(cacheKey, aggregatedReport, CACHE_TTL_MINUTES);

        return aggregatedReport;
    }

    // --- Private Helper Methods ---

    private BillingDashboardDto fetchSingleAccountSummary(CloudAccount account, Integer year, Integer month) {
        try {
            BillingDashboardDto dto = new BillingDashboardDto();
            
            // Fetch Cost History
            List<Map<String, Object>> costHistoryRaw = costService.getCostHistory(account, year, month);
            List<BillingDashboardDto.CostHistory> costHistory = costHistoryRaw.stream()
                    .map(item -> new BillingDashboardDto.CostHistory(
                            (String) item.get("date"),
                            ((Number) Optional.ofNullable(item.get("cost")).orElse(0.0)).doubleValue()
                    )).collect(Collectors.toList());
            dto.setCostHistory(costHistory);

            // Fetch Service Breakdown
            List<Map<String, Object>> serviceBreakdownRaw = costService.getCostByDimension(account, "SERVICE", year, month);
            List<BillingDashboardDto.ServiceBreakdown> serviceBreakdown = serviceBreakdownRaw.stream()
                    .map(item -> new BillingDashboardDto.ServiceBreakdown(
                            (String) item.get("name"),
                            ((Number) Optional.ofNullable(item.get("cost")).orElse(0.0)).doubleValue()
                    )).collect(Collectors.toList());
            dto.setServiceBreakdown(serviceBreakdown);

            return dto;
        } catch (Exception e) {
            logger.error("Error fetching summary for account {}: {}", account.getAwsAccountId(), e.getMessage());
            return new BillingDashboardDto(); // Return empty to avoid breaking the stream
        }
    }

    private List<ServiceCostDetailDto> fetchSingleAccountDetails(CloudAccount account, Integer year, Integer month) {
        List<ServiceCostDetailDto> accountDetails = new ArrayList<>();
        try {
            List<Map<String, Object>> services = costService.getCostByDimension(account, "SERVICE", year, month);
            
            for (Map<String, Object> serviceMap : services) {
                String serviceName = (String) serviceMap.get("name");
                double totalServiceCost = ((Number) Optional.ofNullable(serviceMap.get("cost")).orElse(0.0)).doubleValue();
                
                if (serviceName == null || totalServiceCost <= 0) continue;

                List<RegionCostDto> regionsDto = new ArrayList<>();
                List<Map<String, Object>> regions = costService.getCostForServiceInRegion(account, serviceName, year, month);

                if (regions.isEmpty()) {
                    // Assume Global or fetch without region constraint
                    List<Map<String, Object>> resources = costService.getCostByResource(account, serviceName, "Global", year, month);
                    List<ResourceCostDto> resourceCosts = mapResourcesToDto(resources);
                    if (!resourceCosts.isEmpty()) {
                        regionsDto.add(new RegionCostDto("Global", totalServiceCost, resourceCosts));
                    }
                } else {
                    for (Map<String, Object> regionMap : regions) {
                        String regionName = (String) regionMap.get("name");
                        double regionCost = ((Number) Optional.ofNullable(regionMap.get("cost")).orElse(0.0)).doubleValue();
                        if (regionName == null || regionCost <= 0) continue;

                        List<Map<String, Object>> resources = costService.getCostByResource(account, serviceName, regionName, year, month);
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
        if (d1.getCostHistory() != null) d1.getCostHistory().forEach(h -> historyMap.merge(h.getDate(), h.getCost(), Double::sum));
        if (d2.getCostHistory() != null) d2.getCostHistory().forEach(h -> historyMap.merge(h.getDate(), h.getCost(), Double::sum));
        
        merged.setCostHistory(historyMap.entrySet().stream()
                .map(e -> new BillingDashboardDto.CostHistory(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(BillingDashboardDto.CostHistory::getDate))
                .collect(Collectors.toList()));

        // Merge Services
        Map<String, Double> serviceMap = new HashMap<>();
        if (d1.getServiceBreakdown() != null) d1.getServiceBreakdown().forEach(s -> serviceMap.merge(s.getName(), s.getCost(), Double::sum));
        if (d2.getServiceBreakdown() != null) d2.getServiceBreakdown().forEach(s -> serviceMap.merge(s.getName(), s.getCost(), Double::sum));

        merged.setServiceBreakdown(serviceMap.entrySet().stream()
                .map(e -> new BillingDashboardDto.ServiceBreakdown(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(BillingDashboardDto.ServiceBreakdown::getCost).reversed())
                .collect(Collectors.toList()));

        return merged;
    }

    private ServiceCostDetailDto mergeServiceDetails(List<ServiceCostDetailDto> dtos) {
        if (dtos.isEmpty()) return null;
        ServiceCostDetailDto first = dtos.get(0);
        if (dtos.size() == 1) return first;

        double totalCost = dtos.stream().mapToDouble(ServiceCostDetailDto::getTotalCost).sum();
        
        List<RegionCostDto> allRegions = dtos.stream()
                .flatMap(d -> d.getRegionCosts().stream())
                .collect(Collectors.toList());

        return new ServiceCostDetailDto(first.getServiceName(), totalCost, allRegions);
    }

    private List<ResourceCostDto> mapResourcesToDto(List<Map<String, Object>> resources) {
        if (resources == null) return Collections.emptyList();
        return resources.stream()
            .map(resourceMap -> {
                String rawUsageType = (String) resourceMap.get("name");
                String resourceId = (String) resourceMap.get("id");
                if (rawUsageType == null) return null;

                double cost = ((Number) Optional.ofNullable(resourceMap.get("cost")).orElse(0.0)).doubleValue();
                if (cost <= 0) return null;

                double quantity = ((Number) Optional.ofNullable(resourceMap.get("quantity")).orElse(0.0)).doubleValue();
                String unit = (String) Optional.ofNullable(resourceMap.get("unit")).orElse("");

                return new ResourceCostDto(
                        resourceId != null ? resourceId : rawUsageType,
                        formatUsageType(rawUsageType),
                        cost, quantity, unit
                );
            })
            .filter(Objects::nonNull)
            .sorted(Comparator.comparing(ResourceCostDto::getCost).reversed())
            .collect(Collectors.toList());
    }

    private List<CloudAccount> getUniqueAccounts(List<String> accountIds) {
        List<CloudAccount> allAccounts = cloudAccountRepository.findByAwsAccountIdIn(accountIds);
        Map<String, CloudAccount> uniqueAccountsMap = allAccounts.stream()
                .collect(Collectors.toMap(
                        CloudAccount::getAwsAccountId,
                        Function.identity(),
                        (existing, replacement) -> existing
                ));
        return new ArrayList<>(uniqueAccountsMap.values());
    }

    private String generateCacheKey(String prefix, List<String> accountIds, Integer year, Integer month) {
        List<String> sortedIds = new ArrayList<>(accountIds);
        Collections.sort(sortedIds);
        return prefix + String.join(",", sortedIds) + ":" + year + ":" + month;
    }
    
    // formatUsageType remains the same
    private String formatUsageType(String usageType) {
        if (usageType == null) return "Unknown Usage";
        if (usageType.matches("^[A-Z]{2,4}\\d-.*")) usageType = usageType.substring(usageType.indexOf('-') + 1);
        
        if (usageType.startsWith("BoxUsage:") || usageType.startsWith("InstanceUsage:")) 
            return "EC2 On Demand Linux " + usageType.substring(usageType.indexOf(':') + 1) + " Instance Hour";
            
        if (usageType.startsWith("EBS:")) usageType = usageType.substring(4);
        if (usageType.equalsIgnoreCase("VolumeUsage.gp3")) return "EBS General Purpose SSD (gp3) Volume Storage";
        if (usageType.equalsIgnoreCase("VolumeUsage.gp2")) return "EBS General Purpose SSD (gp2) Volume Storage";
        if (usageType.equalsIgnoreCase("VolumeUsage.io1")) return "EBS Provisioned IOPS SSD (io1) Volume Storage";
        if (usageType.equalsIgnoreCase("VolumeUsage.io2")) return "EBS Provisioned IOPS SSD (io2) Volume Storage";
        if (usageType.equalsIgnoreCase("VolumeUsage.st1")) return "EBS Throughput Optimized HDD (st1) Volume Storage";
        if (usageType.equalsIgnoreCase("VolumeUsage.sc1")) return "EBS Cold HDD (sc1) Volume Storage";
        if (usageType.equalsIgnoreCase("VolumeP-IOPS.io1")) return "EBS Provisioned IOPS SSD (io1) IOPS";
        if (usageType.equalsIgnoreCase("VolumeP-IOPS.io2")) return "EBS Provisioned IOPS SSD (io2) IOPS";

        if (usageType.contains("DataTransfer")) {
            if (usageType.contains("Regional-Bytes")) return "Data Transfer Regional";
            if (usageType.contains("Out-Bytes")) return "Data Transfer Out to Internet";
            if (usageType.contains("In-Bytes")) return "Data Transfer In from Internet";
            return "Data Transfer (Other)";
        }

        if (usageType.startsWith("NatGateway")) {
            if (usageType.endsWith("-Bytes")) return "NAT Gateway Data Processed";
            if (usageType.endsWith("-Hours")) return "NAT Gateway Hourly Charge";
        }

        if (usageType.equalsIgnoreCase("LoadBalancerUsage")) return "ELB Application Load Balancer Hours";
        if (usageType.equalsIgnoreCase("LCUUsage")) return "ELB Application Load Balancer Capacity Units (LCU Hours)";
        if (usageType.contains("NetworkLoadBalancer")) return "ELB Network Load Balancer";

        if (usageType.startsWith("TimedStorage-")) {
             if (usageType.contains("StandardIA")) return "S3 Storage Standard - Infrequent Access";
             if (usageType.contains("OneZoneIA")) return "S3 Storage One Zone - Infrequent Access";
             if (usageType.contains("Glacier")) return "S3 Storage Glacier";
             if (usageType.contains("Intelligent")) return "S3 Storage Intelligent Tiering";
            return "S3 Storage Standard";
        }
        if (usageType.startsWith("Requests-")) return "S3 API Requests (" + usageType.substring(9) + ")";
        if (usageType.equalsIgnoreCase("TimedStorage-ByteHrs")) return "ECR data storage";

        if (usageType.contains("InstanceUsage")) return "RDS Instance Usage";
        if (usageType.contains("StorageUsage")) return "RDS Storage Usage";
        if (usageType.contains("PIOPS")) return "RDS Provisioned IOPS";

        if (usageType.equalsIgnoreCase("APIRequest")) return "API Gateway Requests";
        if (usageType.equalsIgnoreCase("PaidComplianceCheck")) return "Security Hub Compliance Check";
        if (usageType.contains("Config")) return "AWS Config Item Recorded";

        return usageType.replace('-', ' ').replaceAll("(\\p{Ll})(\\p{Lu})", "$1 $2");
    }

    public void evictBillingCaches() {
        logger.info("Cache eviction triggered via TTL or manual key deletion logic if implemented in RedisCacheService.");
    }
}