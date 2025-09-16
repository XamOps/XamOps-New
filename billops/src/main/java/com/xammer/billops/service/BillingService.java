package com.xammer.billops.service;

import com.xammer.billops.domain.CloudAccount;
import com.xammer.billops.dto.BillingDashboardDto;
import com.xammer.billops.dto.RegionCostDto;
import com.xammer.billops.dto.ResourceCostDto;
import com.xammer.billops.dto.ServiceCostDetailDto;
import com.xammer.billops.repository.CloudAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class BillingService {

    private static final Logger logger = LoggerFactory.getLogger(BillingService.class);
    private final CostService costService;
    private final CloudAccountRepository cloudAccountRepository;
    private final ResourceService resourceService;

    public BillingService(CostService costService, CloudAccountRepository cloudAccountRepository, ResourceService resourceService) {
        this.costService = costService;
        this.cloudAccountRepository = cloudAccountRepository;
        this.resourceService = resourceService;
    }

    public BillingDashboardDto getBillingData(List<String> accountIds, Integer year, Integer month) {
        logger.info("Fetching summary billing data for accounts: {}", accountIds);
        List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountIdIn(accountIds);
        if (accounts.isEmpty()) {
            throw new RuntimeException("Accounts not found: " + accountIds);
        }

        BillingDashboardDto data = new BillingDashboardDto();
        List<BillingDashboardDto.CostHistory> combinedCostHistory = new ArrayList<>();
        List<BillingDashboardDto.ServiceBreakdown> combinedServiceBreakdown = new ArrayList<>();

        for (CloudAccount account : accounts) {
            List<Map<String, Object>> costHistoryRaw = costService.getCostHistory(account, year, month);
            combinedCostHistory.addAll(costHistoryRaw.stream()
                    .map(item -> new BillingDashboardDto.CostHistory((String) item.get("date"), (double) item.get("cost")))
                    .collect(Collectors.toList()));

            List<Map<String, Object>> serviceBreakdownRaw = costService.getCostByDimension(account, "SERVICE", year, month);
            combinedServiceBreakdown.addAll(serviceBreakdownRaw.stream()
                    .map(item -> new BillingDashboardDto.ServiceBreakdown((String) item.get("name"), (double) item.get("cost")))
                    .collect(Collectors.toList()));
        }

        data.setCostHistory(combinedCostHistory);
        data.setServiceBreakdown(combinedServiceBreakdown);

        return data;
    }

    public List<ServiceCostDetailDto> getDetailedBillingReport(List<String> accountIds, Integer year, Integer month) {
        logger.info("--- Starting Detailed Billing Report Generation (Cost-Only Method) ---");
        List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountIdIn(accountIds);
        if (accounts.isEmpty()) {
            throw new RuntimeException("Accounts not found: " + accountIds);
        }

        List<ServiceCostDetailDto> detailedReport = new ArrayList<>();
        for (CloudAccount account : accounts) {
            List<Map<String, Object>> services = costService.getCostByDimension(account, "SERVICE", year, month);
            logger.info("STEP 1: Found {} services in total for account {}.", services.size(), account.getAwsAccountId());

            for (Map<String, Object> serviceMap : services) {
                String serviceName = (String) serviceMap.get("name");
                double totalServiceCost = (double) serviceMap.get("cost");
                logger.info("--> STEP 2: Processing Service '{}' with cost ${}", serviceName, totalServiceCost);

                List<RegionCostDto> allRegionCosts = new ArrayList<>();
                List<Map<String, Object>> regions = costService.getCostForServiceInRegion(account, serviceName, year, month);
                logger.info("    STEP 3: Found {} regions for service '{}'.", regions.size(), serviceName);

                if (regions.isEmpty()) {
                    logger.info("    No regional data for '{}'. Fetching usage types directly.", serviceName);
                    List<Map<String, Object>> resources = costService.getCostByResource(account, serviceName, null, year, month);
                    List<ResourceCostDto> resourceCosts = resources.stream()
                            .map(resourceMap -> {
                                String rawUsageType = (String) resourceMap.get("name");
                                return new ResourceCostDto(
                                        rawUsageType,
                                        formatUsageType(rawUsageType),
                                        (double) resourceMap.get("cost"),
                                        (double) resourceMap.get("quantity"),
                                        (String) resourceMap.get("unit")
                                );
                            })
                            .collect(Collectors.toList());
                    if (!resourceCosts.isEmpty()) {
                        allRegionCosts.add(new RegionCostDto("Global", totalServiceCost, resourceCosts));
                    }
                } else {
                    for (Map<String, Object> regionMap : regions) {
                        String regionName = (String) regionMap.get("name");
                        double totalRegionCost = (double) regionMap.get("cost");
                        logger.info("    ----> STEP 4: Processing Region '{}' with cost ${}", regionName, totalRegionCost);
                        List<Map<String, Object>> resources = costService.getCostByResource(account, serviceName, regionName, year, month);
                        List<ResourceCostDto> resourceCosts = resources.stream()
                                .map(resourceMap -> {
                                    String rawUsageType = (String) resourceMap.get("name");
                                    return new ResourceCostDto(
                                            rawUsageType,
                                            formatUsageType(rawUsageType),
                                            (double) resourceMap.get("cost"),
                                            (double) resourceMap.get("quantity"),
                                            (String) resourceMap.get("unit")
                                    );
                                })
                                .collect(Collectors.toList());
                        logger.info("        --> Found {} resource details for '{}' in '{}'", resourceCosts.size(), serviceName, regionName);
                        allRegionCosts.add(new RegionCostDto(regionName, totalRegionCost, resourceCosts));
                    }
                }
                detailedReport.add(new ServiceCostDetailDto(serviceName, totalServiceCost, allRegionCosts));
            }
        }
        logger.info("--- Finished Detailed Billing Report Generation. Returning {} top-level service entries. ---", detailedReport.size());
        return detailedReport;
    }

    private String formatUsageType(String usageType) {
        // Remove region prefix (e.g., "APS3-")
        if (usageType.matches("^[A-Z]{2,4}\\d-.*")) {
            usageType = usageType.substring(usageType.indexOf('-') + 1);
        }

        // EC2 Instances
        if (usageType.startsWith("BoxUsage:")) {
            return "On Demand Linux " + usageType.substring(usageType.indexOf(':') + 1) + " Instance Hour";
        }

        // EBS Volumes & Snapshots
        if (usageType.startsWith("EBS:")) {
            usageType = usageType.substring(4);
        }
        if (usageType.equals("SnapshotUsage")) return "EBS Snapshot Storage";
        if (usageType.equals("VolumeUsage.gp3")) return "General Purpose (gp3) provisioned storage";
        if (usageType.equals("VolumeUsage.gp2")) return "General Purpose (gp2) provisioned storage";

        // Data Transfer
        if (usageType.contains("DataTransfer")) {
            if (usageType.contains("Regional-Bytes")) return "Regional Data Transfer";
            return "Data Transfer Out";
        }

        // NAT Gateway
        if (usageType.startsWith("NatGateway")) {
            if (usageType.endsWith("-Bytes")) return "Data Processed by NAT Gateways";
            if (usageType.endsWith("-Hours")) return "NAT Gateway Hour";
        }

        // Load Balancer
        if (usageType.equals("LoadBalancerUsage")) return "Application LoadBalancer-hour";
        if (usageType.equals("LCUUsage")) return "Application load balancer capacity unit-hour";

        // ECR
        if (usageType.equals("TimedStorage-ByteHrs")) return "ECR data storage";

        // Other known types
        if (usageType.equals("APIRequest")) return "API Request";
        if (usageType.equals("PaidComplianceCheck")) return "Security Hub Compliance Check";

        // Fallback for anything not specifically matched
        return usageType.replace('-', ' ');
    }

    // public List<ServiceCostDetailDto> getDetailedBillingReport(List<String> accountIds, Integer year, Integer month) {
    //     // TODO Auto-generated method stub
    //     throw new UnsupportedOperationException("Unimplemented method 'getDetailedBillingReport'");
    // }
}