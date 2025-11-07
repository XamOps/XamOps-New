package com.xammer.cloud.service.azure;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.FinOpsReportSchedule;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.dto.FinOpsReportScheduleDto;
import com.xammer.cloud.dto.azure.AzureDashboardData;
import com.xammer.cloud.dto.azure.AzureFinOpsReportDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.FinOpsReportScheduleRepository;
import com.xammer.cloud.service.CostService;
import com.xammer.cloud.service.RedisCacheService;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class AzureFinOpsService {

    private static final Logger logger = LoggerFactory.getLogger(AzureFinOpsService.class);

    @Autowired
    private AzureCostManagementService costManagementService;

    @Autowired
    private CloudAccountRepository cloudAccountRepository;

    @Autowired
    private FinOpsReportScheduleRepository scheduleRepository;

    @Autowired
    private RedisCacheService redisCacheService;

    @Autowired
    private CostService costService;

    /**
     * Generates the main FinOps report for an Azure subscription
     */
    public AzureFinOpsReportDto getFinOpsReport(String subscriptionId) {
        logger.info("Generating Azure FinOps report for subscription: {}", subscriptionId);

        AzureFinOpsReportDto report = new AzureFinOpsReportDto();
        AzureFinOpsReportDto.Kpis kpis = new AzureFinOpsReportDto.Kpis();

        String summaryCacheKey = "azure:billing-summary:" + subscriptionId;
        String historyCacheKey = "azure:cost-history:" + subscriptionId;
        String regionCacheKey = AzureBillingDataIngestionService.AZURE_COST_BY_REGION_CACHE_PREFIX + subscriptionId;

        // Get billing summary from cache
        Optional<List<AzureDashboardData.BillingSummary>> billingSummaryOpt =
            redisCacheService.get(summaryCacheKey, new TypeReference<>() {});

        // Get cost history from cache
        Optional<AzureDashboardData.CostHistory> costHistoryOpt =
            redisCacheService.get(historyCacheKey, AzureDashboardData.CostHistory.class);

        // --- STEP 1: Get KPIs from Redis Cache ---
        try {
            // Calculate Total Spend
            if (billingSummaryOpt.isPresent()) {
                double totalSpend = billingSummaryOpt.get().stream()
                        .mapToDouble(AzureDashboardData.BillingSummary::getCost)
                        .sum();
                kpis.setTotalSpend(totalSpend);
            } else {
                kpis.setTotalSpend(0.0);
            }

            // Calculate Forecasted Spend from cost history cache
            if (costHistoryOpt.isPresent()) {
                AzureDashboardData.CostHistory costHistory = costHistoryOpt.get();
                List<String> labels = costHistory.getLabels();
                List<Double> costs = costHistory.getCosts();
                List<Boolean> anomalies = costHistory.getAnomalies();

                if (labels != null && costs != null && anomalies != null && labels.size() == costs.size()) {

                    // Calculate Forecasted Spend (where anomaly is true)
                    double totalForecast = IntStream.range(0, labels.size())
                        .filter(i -> Boolean.TRUE.equals(anomalies.get(i))) // Filter FOR forecast data
                        .mapToDouble(costs::get)
                        .sum();
                    
                    kpis.setForecastedSpend(totalForecast);
                    logger.info("Extracted forecast from cached cost history for {}: ${}", subscriptionId, totalForecast);

                    // Calculate MoM/YoY (where anomaly is false)
                    Map<String, Double> historyMap = IntStream.range(0, labels.size())
                        .filter(i -> !Boolean.TRUE.equals(anomalies.get(i))) // Filter OUT forecast data
                        .boxed()
                        .collect(Collectors.toMap(
                            i -> labels.get(i),
                            i -> costs.get(i),
                            (a, b) -> b
                        ));

                    // TODO: Implement calculateMomAndYoyChanges in CostService
                    // For now, setting to 0
                    logger.warn("FIXME: costService.calculateMomAndYoyChanges is not implemented. MoM/YoY will be 0.");
                    kpis.setMomChange(0.0);
                    kpis.setYoyChange(0.0);
                } else {
                    // Set defaults if cost history data is malformed
                    kpis.setForecastedSpend(0.0);
                    kpis.setMomChange(0.0);
                    kpis.setYoyChange(0.0);
                }
            } else {
                // Set defaults if cost history is missing entirely
                kpis.setForecastedSpend(0.0);
                kpis.setMomChange(0.0);
                kpis.setYoyChange(0.0);
            }
        } catch (Exception e) {
            logger.error("Failed to get KPIs from cache for Azure subscription {}: {}", subscriptionId, e.getMessage());
            kpis.setTotalSpend(0.0);
            kpis.setForecastedSpend(0.0);
            kpis.setMomChange(0.0);
            kpis.setYoyChange(0.0);
        }
        report.setKpis(kpis);

        // --- STEP 2: Get Cost Breakdown (Cost by Service) ---
        try {
            if (billingSummaryOpt.isPresent()) {
                List<AzureFinOpsReportDto.CostBreakdown> breakdownList = billingSummaryOpt.get().stream()
                    .map(item -> new AzureFinOpsReportDto.CostBreakdown(
                        item.getService(),
                        item.getCost(),
                        0.0  // MoM Change (Placeholder)
                    ))
                    .collect(Collectors.toList());

                report.setCostBreakdown(breakdownList);
                logger.info("Added {} services to cost breakdown", breakdownList.size());
            } else {
                report.setCostBreakdown(Collections.emptyList());
            }
        } catch (Exception e) {
            logger.error("Failed to get Cost Breakdown for Azure subscription {}: {}", subscriptionId, e.getMessage());
            report.setCostBreakdown(new ArrayList<>());
        }

        // --- STEP 3: Get Cost by Region ---
        try {
            Optional<Map<String, Double>> costByRegionOpt = 
                redisCacheService.get(regionCacheKey, new TypeReference<Map<String, Double>>() {});

            if (costByRegionOpt.isPresent()) {
                Map<String, Double> costByRegion = costByRegionOpt.get();
                
                // Convert to DTO list (sorted by cost descending)
                List<AzureFinOpsReportDto.CostByRegion> regionList = costByRegion.entrySet().stream()
                    .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                    .map(entry -> new AzureFinOpsReportDto.CostByRegion(
                        entry.getKey(),    // region name
                        entry.getValue()   // cost
                    ))
                    .collect(Collectors.toList());
                
                report.setCostByRegion(regionList);
                logger.info("Added {} regions to FinOps report", regionList.size());
            } else {
                report.setCostByRegion(Collections.emptyList());
                logger.warn("No cost by region data found in cache for {}", subscriptionId);
            }
        } catch (Exception e) {
            logger.error("Failed to get cost by region for {}: {}", subscriptionId, e.getMessage());
            report.setCostByRegion(Collections.emptyList());
        }

        return report;
    }

    /**
     * Retrieves all report schedules for a specific Azure subscription and user.
     */
    public List<FinOpsReportScheduleDto> getSchedules(String subscriptionId, User user) {
        CloudAccount account = findAccountBySubscriptionId(subscriptionId);
        List<FinOpsReportSchedule> schedules = scheduleRepository.findByUserIdAndCloudAccountId(user.getId(), account.getId());
        return schedules.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    /**
     * Creates a new report schedule.
     */
    public FinOpsReportScheduleDto createSchedule(FinOpsReportScheduleDto dto, User user) {
        CloudAccount account = findAccountBySubscriptionId(dto.getCloudAccountId());

        FinOpsReportSchedule schedule = new FinOpsReportSchedule();
        schedule.setUser(user);
        schedule.setCloudAccount(account);
        schedule.setEmail(dto.getEmail());

        schedule.setFrequency(FinOpsReportSchedule.Frequency.valueOf(dto.getFrequency().toUpperCase()));
        schedule.setActive(true);

        // TODO: You still need to set the cronExpression field here before saving
        // schedule.setCronExpression(...);

        FinOpsReportSchedule savedSchedule = scheduleRepository.save(schedule);

        logger.info("Created new FinOps schedule {} for user {} and subscription {}",
                savedSchedule.getId(), user.getUsername(), dto.getCloudAccountId());

        return convertToDto(savedSchedule);
    }

    /**
     * Deletes a report schedule after verifying ownership.
     */
    public void deleteSchedule(Long scheduleId, User user) {
        FinOpsReportSchedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new RuntimeException("Schedule not found"));

        if (!schedule.getUser().getId().equals(user.getId())) {
            logger.warn("User {} attempted to delete schedule {} owned by {}",
                    user.getUsername(), scheduleId, schedule.getUser().getUsername());
            throw new AccessDeniedException("You do not have permission to delete this schedule.");
        }

        scheduleRepository.delete(schedule);
        logger.info("Deleted FinOps schedule {} for user {}", scheduleId, user.getUsername());
    }

    // --- Helper Methods ---

    private CloudAccount findAccountBySubscriptionId(String subscriptionId) {
        return cloudAccountRepository.findByAzureSubscriptionId(subscriptionId)
                .orElseThrow(() -> new RuntimeException("Azure account not found for subscription ID: " + subscriptionId));
    }

    private FinOpsReportScheduleDto convertToDto(FinOpsReportSchedule entity) {
        FinOpsReportScheduleDto dto = new FinOpsReportScheduleDto();
        dto.setId(entity.getId());
        dto.setEmail(entity.getEmail());

        if (entity.getFrequency() != null) {
            dto.setFrequency(entity.getFrequency().name());
        }

        if (entity.getCloudAccount() != null) {
            dto.setCloudAccountId(entity.getCloudAccount().getProviderAccountId());
            dto.setAccountName(entity.getCloudAccount().getAccountName());
        }

        dto.setActive(entity.isActive());

        return dto;
    }
}
