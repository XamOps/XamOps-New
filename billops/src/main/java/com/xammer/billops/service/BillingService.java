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

    public BillingService(CostService costService, CloudAccountRepository cloudAccountRepository) {
        this.costService = costService;
        this.cloudAccountRepository = cloudAccountRepository;
    }

    public BillingDashboardDto getBillingData(String accountId, Integer year, Integer month) {
        logger.info("Fetching summary billing data for account: {}", accountId);
        CloudAccount account = cloudAccountRepository.findByAwsAccountId(accountId)
            .orElseThrow(() -> new RuntimeException("Account not found: " + accountId));

        BillingDashboardDto data = new BillingDashboardDto();

        List<Map<String, Object>> costHistoryRaw = costService.getCostHistory(account, year, month);
        List<BillingDashboardDto.CostHistory> costHistory = costHistoryRaw.stream()
            .map(item -> new BillingDashboardDto.CostHistory((String) item.get("date"), (double) item.get("cost")))
            .collect(Collectors.toList());
        data.setCostHistory(costHistory);

        List<Map<String, Object>> serviceBreakdownRaw = costService.getCostByDimension(account, "SERVICE", year, month);
        List<BillingDashboardDto.ServiceBreakdown> serviceBreakdown = serviceBreakdownRaw.stream()
            .map(item -> new BillingDashboardDto.ServiceBreakdown((String) item.get("name"), (double) item.get("cost")))
            .collect(Collectors.toList());
        data.setServiceBreakdown(serviceBreakdown);

        return data;
    }

    // ### MODIFIED THIS METHOD WITH LOGGING ###
    public List<ServiceCostDetailDto> getDetailedBillingReport(String accountId, Integer year, Integer month) {
        logger.info("--- Starting Detailed Billing Report Generation ---");
        CloudAccount account = cloudAccountRepository.findByAwsAccountId(accountId)
                .orElseThrow(() -> new RuntimeException("Account not found: " + accountId));

        List<ServiceCostDetailDto> detailedReport = new ArrayList<>();

        // 1. Get top-level services
        List<Map<String, Object>> services = costService.getCostByDimension(account, "SERVICE", year, month);
        logger.info("STEP 1: Found {} services in total.", services.size());

        // 2. Loop through each service sequentially
        for (Map<String, Object> serviceMap : services) {
            String serviceName = (String) serviceMap.get("name");
            double totalServiceCost = (double) serviceMap.get("cost");
            logger.info("--> STEP 2: Processing Service '{}' with cost ${}", serviceName, totalServiceCost);

            List<RegionCostDto> regionCosts = new ArrayList<>();

            // 3. For the current service, get its breakdown by region
            List<Map<String, Object>> regions = costService.getCostForServiceInRegion(account, serviceName, year, month);
            logger.info("    STEP 3: Found {} regions for service '{}'.", regions.size(), serviceName);

            // 4. Loop through each region
            for (Map<String, Object> regionMap : regions) {
                String regionName = (String) regionMap.get("name");
                double totalRegionCost = (double) regionMap.get("cost");
                logger.info("    ----> STEP 4: Processing Region '{}' with cost ${}", regionName, totalRegionCost);

                // 5. For the current service and region, get its resources (usage types)
                List<Map<String, Object>> resources = costService.getCostByResource(account, serviceName, regionName, year, month);
                logger.info("        STEP 5: Found {} resources/usage types for service '{}' in region '{}'.", resources.size(), serviceName, regionName);
                
                List<ResourceCostDto> resourceCosts = resources.stream()
                        .map(resourceMap -> new ResourceCostDto(
                                (String) resourceMap.get("id"),
                                (String) resourceMap.get("name"),
                                (double) resourceMap.get("cost")))
                        .collect(Collectors.toList());
                
                regionCosts.add(new RegionCostDto(regionName, totalRegionCost, resourceCosts));
            }
            
            detailedReport.add(new ServiceCostDetailDto(serviceName, totalServiceCost, regionCosts));
        }

        logger.info("--- Finished Detailed Billing Report Generation. Returning {} top-level service entries. ---", detailedReport.size());
        return detailedReport;
    }
}