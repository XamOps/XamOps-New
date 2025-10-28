package com.xammer.billops.service;

import com.xammer.billops.domain.CloudAccount;
import com.xammer.billops.dto.BillingDashboardDto;
import com.xammer.billops.dto.RegionCostDto;
import com.xammer.billops.dto.ResourceCostDto;
import com.xammer.billops.dto.ServiceCostDetailDto;
import com.xammer.billops.repository.CloudAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.*; // Import Optional explicitly
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class BillingService {

    private static final Logger logger = LoggerFactory.getLogger(BillingService.class);
    private final CostService costService;
    private final CloudAccountRepository cloudAccountRepository;
    private final ResourceService resourceService;

    // Define cache names as constants for consistency
    private static final String BILLING_DASHBOARD_CACHE = "billingDashboard";
    private static final String DETAILED_BILLING_REPORT_CACHE = "detailedBillingReport";


    public BillingService(CostService costService, CloudAccountRepository cloudAccountRepository, ResourceService resourceService) {
        this.costService = costService;
        this.cloudAccountRepository = cloudAccountRepository;
        this.resourceService = resourceService;
    }

    private List<CloudAccount> getUniqueAccounts(List<String> accountIds) {
        List<CloudAccount> allAccounts = cloudAccountRepository.findByAwsAccountIdIn(accountIds);
        if (allAccounts.isEmpty()) {
            // Log a warning instead of throwing an exception immediately,
            // allows frontend to handle "no accounts found" more gracefully.
            logger.warn("No accounts found for IDs: {}", accountIds);
            return Collections.emptyList();
            // throw new RuntimeException("Accounts not found: " + accountIds);
        }

        Map<String, CloudAccount> uniqueAccountsMap = allAccounts.stream()
                .collect(Collectors.toMap(
                        CloudAccount::getAwsAccountId,
                        Function.identity(),
                        (existing, replacement) -> existing // Keep the first encountered account for a given ID
                ));

        return new ArrayList<>(uniqueAccountsMap.values());
    }

    // --- NEW: Cache-only method for summary data ---
    @Cacheable(value = BILLING_DASHBOARD_CACHE, key = "{#accountIds, #year, #month}")
    public Optional<BillingDashboardDto> getCachedBillingData(List<String> accountIds, Integer year, Integer month) {
        logger.debug("Attempting to retrieve cached summary billing data for accounts: {}, period: {}-{}", accountIds, year, month);
        // This method relies entirely on the @Cacheable annotation.
        // Spring will return the cached value if present, or null/Optional.empty() if not.
        // We return Optional to make it explicit that data might not be in the cache.
        return Optional.empty(); // Spring AOP replaces this with cache lookup logic
    }


    // --- MODIFIED: Method to fetch fresh data AND update cache ---
    @CachePut(value = BILLING_DASHBOARD_CACHE, key = "{#accountIds, #year, #month}")
    public Optional<BillingDashboardDto> getBillingDataAndCache(List<String> accountIds, Integer year, Integer month) {
        logger.info("Fetching FRESH summary billing data for accounts: {} and updating cache", accountIds);
        List<CloudAccount> accounts = getUniqueAccounts(accountIds);
        if (accounts.isEmpty()) {
            logger.warn("No valid accounts found for IDs: {}. Returning empty data.", accountIds);
            // Return empty Optional if no accounts are found to avoid caching null for valid parameters
            return Optional.of(new BillingDashboardDto());
        }


        BillingDashboardDto data = new BillingDashboardDto();
        List<BillingDashboardDto.CostHistory> combinedCostHistory = new ArrayList<>();
        List<BillingDashboardDto.ServiceBreakdown> combinedServiceBreakdown = new ArrayList<>();

        // Aggregate data from all valid accounts
        for (CloudAccount account : accounts) {
            try {
                List<Map<String, Object>> costHistoryRaw = costService.getCostHistory(account, year, month);
                combinedCostHistory.addAll(costHistoryRaw.stream()
                        .map(item -> new BillingDashboardDto.CostHistory(
                                (String) item.get("date"),
                                ((Number) Optional.ofNullable(item.get("cost")).orElse(0.0)).doubleValue() // Handle potential null cost
                        ))
                        .collect(Collectors.toList()));


                List<Map<String, Object>> serviceBreakdownRaw = costService.getCostByDimension(account, "SERVICE", year, month);
                 combinedServiceBreakdown.addAll(serviceBreakdownRaw.stream()
                        .map(item -> new BillingDashboardDto.ServiceBreakdown(
                                (String) item.get("name"),
                                ((Number) Optional.ofNullable(item.get("cost")).orElse(0.0)).doubleValue() // Handle potential null cost
                        ))
                        .collect(Collectors.toList()));
            } catch (Exception e) {
                 logger.error("Error fetching data for account {}: {}", account.getAwsAccountId(), e.getMessage(), e);
                 // Optionally continue to next account or rethrow depending on desired behavior
            }
        }


        // Consolidate potentially duplicate entries if multiple accounts report same period/service
        // For cost history, maybe averaging or summing makes sense? Summing for now.
         Map<String, Double> consolidatedHistory = combinedCostHistory.stream()
            .collect(Collectors.groupingBy(
                BillingDashboardDto.CostHistory::getDate,
                Collectors.summingDouble(BillingDashboardDto.CostHistory::getCost)
            ));
        List<BillingDashboardDto.CostHistory> finalCostHistory = consolidatedHistory.entrySet().stream()
            .map(entry -> new BillingDashboardDto.CostHistory(entry.getKey(), entry.getValue()))
            .sorted(Comparator.comparing(BillingDashboardDto.CostHistory::getDate)) // Ensure chronological order
            .collect(Collectors.toList());


        // For service breakdown, sum costs for the same service across accounts
         Map<String, Double> consolidatedServices = combinedServiceBreakdown.stream()
            .collect(Collectors.groupingBy(
                BillingDashboardDto.ServiceBreakdown::getName,
                Collectors.summingDouble(BillingDashboardDto.ServiceBreakdown::getCost)
            ));
        List<BillingDashboardDto.ServiceBreakdown> finalServiceBreakdown = consolidatedServices.entrySet().stream()
            .map(entry -> new BillingDashboardDto.ServiceBreakdown(entry.getKey(), entry.getValue()))
             .sorted(Comparator.comparing(BillingDashboardDto.ServiceBreakdown::getCost).reversed()) // Sort by cost descending
            .collect(Collectors.toList());


        data.setCostHistory(finalCostHistory);
        data.setServiceBreakdown(finalServiceBreakdown);

        return Optional.of(data); // Return Optional to match @CachePut expectation if needed
    }

    // --- NEW: Cache-only method for detailed data ---
    @Cacheable(value = DETAILED_BILLING_REPORT_CACHE, key = "{#accountIds, #year, #month}")
    public Optional<List<ServiceCostDetailDto>> getCachedDetailedBillingReport(List<String> accountIds, Integer year, Integer month) {
        logger.debug("Attempting to retrieve cached detailed billing data for accounts: {}, period: {}-{}", accountIds, year, month);
        // Relies on @Cacheable
        return Optional.empty(); // Spring AOP replaces this
    }

    // --- MODIFIED: Method to fetch fresh detailed data AND update cache ---
    @CachePut(value = DETAILED_BILLING_REPORT_CACHE, key = "{#accountIds, #year, #month}")
    public Optional<List<ServiceCostDetailDto>> getDetailedBillingReportAndCache(List<String> accountIds, Integer year, Integer month) {
        logger.info("--- Starting FRESH Detailed Billing Report Generation for accounts {} and updating cache ---", accountIds);
        List<CloudAccount> accounts = getUniqueAccounts(accountIds);
         if (accounts.isEmpty()) {
            logger.warn("No valid accounts found for IDs: {}. Returning empty detailed report.", accountIds);
            return Optional.of(Collections.emptyList()); // Return empty list for caching
        }


        List<ServiceCostDetailDto> detailedReport = new ArrayList<>();
        for (CloudAccount account : accounts) {
             try {
                logger.info("Processing account: {}", account.getAwsAccountId());
                List<Map<String, Object>> services = costService.getCostByDimension(account, "SERVICE", year, month);
                logger.debug("STEP 1: Found {} services in total for account {}.", services.size(), account.getAwsAccountId());

                for (Map<String, Object> serviceMap : services) {
                    String serviceName = (String) serviceMap.get("name");
                    // Handle potential null cost gracefully
                    double totalServiceCost = ((Number) Optional.ofNullable(serviceMap.get("cost")).orElse(0.0)).doubleValue();
                    if (serviceName == null || totalServiceCost <= 0) { // Skip if service name is null or cost is zero/negative
                        logger.debug("Skipping service with null name or zero/negative cost: {}", serviceMap);
                        continue;
                    }

                    logger.debug("--> STEP 2: Processing Service '{}' with cost ${}", serviceName, totalServiceCost);

                    List<RegionCostDto> allRegionCosts = new ArrayList<>();
                    List<Map<String, Object>> regions = costService.getCostForServiceInRegion(account, serviceName, year, month);
                    logger.debug("     STEP 3: Found {} regions for service '{}'.", regions.size(), serviceName);

                    if (regions.isEmpty()) {
                        logger.debug("     No specific regional data for '{}'. Fetching usage types directly (assuming global or single region).", serviceName);
                        // Fetch resources without a region filter (or maybe default to a known region if appropriate)
                        List<Map<String, Object>> resources = costService.getCostByResource(account, serviceName, "Global", year, month); // Pass null or a default like "Global"
                         List<ResourceCostDto> resourceCosts = mapResourcesToDto(resources);
                         if (!resourceCosts.isEmpty()) {
                            // Assign total service cost to the "Global" region if no specific regions were found
                            allRegionCosts.add(new RegionCostDto("Global", totalServiceCost, resourceCosts));
                         }
                    } else {
                        for (Map<String, Object> regionMap : regions) {
                            String regionName = (String) regionMap.get("name");
                             // Handle null region cost
                            double totalRegionCost = ((Number) Optional.ofNullable(regionMap.get("cost")).orElse(0.0)).doubleValue();

                             if (regionName == null || totalRegionCost <= 0) {
                                logger.debug("Skipping region with null name or zero/negative cost for service '{}': {}", serviceName, regionMap);
                                continue;
                            }


                            logger.debug("     ----> STEP 4: Processing Region '{}' with cost ${}", regionName, totalRegionCost);
                            List<Map<String, Object>> resources = costService.getCostByResource(account, serviceName, regionName, year, month);
                            List<ResourceCostDto> resourceCosts = mapResourcesToDto(resources);
                            logger.debug("         --> Found {} resource details for '{}' in '{}'", resourceCosts.size(), serviceName, regionName);
                            allRegionCosts.add(new RegionCostDto(regionName, totalRegionCost, resourceCosts));
                        }
                    }
                     // Only add service if it has valid region costs
                    if (!allRegionCosts.isEmpty()) {
                        detailedReport.add(new ServiceCostDetailDto(serviceName, totalServiceCost, allRegionCosts));
                    }
                }
             } catch (Exception e) {
                 logger.error("Error processing detailed report for account {}: {}", account.getAwsAccountId(), e.getMessage(), e);
                 // Optionally continue or rethrow
             }
        }
        logger.info("--- Finished Detailed Billing Report Generation. Returning {} top-level service entries. ---", detailedReport.size());
        return Optional.of(detailedReport); // Return Optional to match @CachePut
    }


     // Helper method to map raw resource data to DTOs
    private List<ResourceCostDto> mapResourcesToDto(List<Map<String, Object>> resources) {
        if (resources == null) {
            return Collections.emptyList();
        }
        return resources.stream()
            .map(resourceMap -> {
                String rawUsageType = (String) resourceMap.get("name"); // Cost Explorer often uses 'name' for the usage type key
                String resourceId = (String) resourceMap.get("id"); // Cost Explorer often uses 'id' for the usage type key as well
                if (rawUsageType == null) return null; // Skip if usage type is missing

                double cost = ((Number) Optional.ofNullable(resourceMap.get("cost")).orElse(0.0)).doubleValue();
                // Skip if cost is zero or negative
                if (cost <= 0) return null;

                double quantity = ((Number) Optional.ofNullable(resourceMap.get("quantity")).orElse(0.0)).doubleValue();
                String unit = (String) Optional.ofNullable(resourceMap.get("unit")).orElse("");


                return new ResourceCostDto(
                        resourceId != null ? resourceId : rawUsageType, // Use ID if available, else usage type
                        formatUsageType(rawUsageType), // Formatted name
                        cost,
                        quantity,
                        unit
                );
            })
            .filter(Objects::nonNull) // Filter out null entries from skipped items
            .sorted(Comparator.comparing(ResourceCostDto::getCost).reversed()) // Sort by cost descending
            .collect(Collectors.toList());
    }



    @CacheEvict(value = {BILLING_DASHBOARD_CACHE, DETAILED_BILLING_REPORT_CACHE}, allEntries = true)
    public void evictBillingCaches() {
        logger.info("Evicting all billing caches...");
    }

    // formatUsageType remains the same as before
    private String formatUsageType(String usageType) {
         if (usageType == null) return "Unknown Usage"; // Handle null input

        if (usageType.matches("^[A-Z]{2,4}\\d-.*")) {
            usageType = usageType.substring(usageType.indexOf('-') + 1);
        }

        if (usageType.startsWith("BoxUsage:")) {
            return "EC2 On Demand Linux " + usageType.substring(usageType.indexOf(':') + 1) + " Instance Hour";
        }
         if (usageType.startsWith("InstanceUsage:")) { // Handle potential alternative prefix
            return "EC2 On Demand Linux " + usageType.substring(usageType.indexOf(':') + 1) + " Instance Hour";
        }


        if (usageType.startsWith("EBS:")) {
            usageType = usageType.substring(4);
        }
        if (usageType.equalsIgnoreCase("SnapshotUsage")) return "EBS Snapshot Storage";
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
             // Add more specific DataTransfer types if needed
            return "Data Transfer (Other)";
        }


        if (usageType.startsWith("NatGateway")) {
            if (usageType.endsWith("-Bytes")) return "NAT Gateway Data Processed";
            if (usageType.endsWith("-Hours")) return "NAT Gateway Hourly Charge";
        }

         // ELB usage types
        if (usageType.equalsIgnoreCase("LoadBalancerUsage")) return "ELB Application Load Balancer Hours";
        if (usageType.equalsIgnoreCase("LCUUsage")) return "ELB Application Load Balancer Capacity Units (LCU Hours)";
         if (usageType.contains("NetworkLoadBalancer")) return "ELB Network Load Balancer"; // Simplify for now

        // S3 usage types
        if (usageType.startsWith("TimedStorage-")) {
             if (usageType.contains("StandardIA")) return "S3 Storage Standard - Infrequent Access";
             if (usageType.contains("OneZoneIA")) return "S3 Storage One Zone - Infrequent Access";
             if (usageType.contains("Glacier")) return "S3 Storage Glacier"; // Includes Deep Archive often
             if (usageType.contains("Intelligent")) return "S3 Storage Intelligent Tiering";
            return "S3 Storage Standard"; // Default for TimedStorage
        }
        if (usageType.startsWith("Requests-")) return "S3 API Requests (" + usageType.substring(9) + ")";


        if (usageType.equalsIgnoreCase("TimedStorage-ByteHrs")) return "ECR data storage"; // Often ECR

         // RDS usage types - often complex, simplify for now
        if (usageType.contains("InstanceUsage")) return "RDS Instance Usage";
        if (usageType.contains("StorageUsage")) return "RDS Storage Usage";
        if (usageType.contains("PIOPS")) return "RDS Provisioned IOPS";


        if (usageType.equalsIgnoreCase("APIRequest")) return "API Gateway Requests";
        if (usageType.equalsIgnoreCase("PaidComplianceCheck")) return "Security Hub Compliance Check";
         if (usageType.contains("Config")) return "AWS Config Item Recorded"; // Simplify

        // Generic fallback - replace hyphens and try to make readable
        return usageType.replace('-', ' ').replaceAll("(\\p{Ll})(\\p{Lu})", "$1 $2"); // Add space before capitals
    }


}