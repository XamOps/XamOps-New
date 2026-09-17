package com.xammer.cloud.service.azure;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.FinOpsReportSchedule;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.dto.FinOpsReportScheduleDto;
import com.xammer.cloud.dto.azure.AzureBudgetDto;
import com.xammer.cloud.dto.azure.AzureDashboardData;
import com.xammer.cloud.dto.azure.AzureFinOpsReportDto;
import com.xammer.cloud.dto.azure.AzureRightsizingRecommendation;
import com.xammer.cloud.dto.azure.AzureRightsizingResponse;
import com.xammer.cloud.dto.azure.AzureWasteItem;
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

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class AzureFinOpsService {

    private static final Logger logger = LoggerFactory.getLogger(AzureFinOpsService.class);

    @Autowired
    private AzureCostManagementService costManagementService;

    @Autowired
    private AzureRightsizingService rightsizingService;

    @Autowired
    private AzureWasteService wasteService;

    @Autowired
    private CloudAccountRepository cloudAccountRepository;

    @Autowired
    private FinOpsReportScheduleRepository scheduleRepository;

    @Autowired
    private RedisCacheService redisCacheService;

    @Autowired
    private CostService costService;

    @Autowired
    private AzureCostQueryService costQueryService;

    @Autowired
    private AzureBudgetService azureBudgetService;

    @Autowired
    private AzureCostAnomalyService costAnomalyService;

    /**
     * Owns the cost cache keys, and now the Redis-then-database read for them.
     *
     * <p>
     * Injected so this report stops reading Redis directly. Both tiers are written by the same
     * ingestion pass, but only {@code AzureCostService} ever consulted the database copy and no
     * page uses it — so a cold Redis key made this report show zero spend while the correct figure
     * sat in {@code cached_data}.
     */
    @Autowired
    private AzureBillingDataIngestionService billingIngestionService;

    /**
     * Generates the main FinOps report for an Azure subscription
     */
    public AzureFinOpsReportDto getFinOpsReport(String subscriptionId) {
        logger.info("Generating Azure FinOps report for subscription: {}", subscriptionId);

        AzureFinOpsReportDto report = new AzureFinOpsReportDto();
        AzureFinOpsReportDto.Kpis kpis = new AzureFinOpsReportDto.Kpis();

        String regionCacheKey = AzureBillingDataIngestionService.AZURE_COST_BY_REGION_CACHE_PREFIX + subscriptionId;

        // Redis, then the database mirror.
        //
        // These were bare Redis reads, so a cold or evicted key made this report show zero spend
        // even though the ingestion job had written the same payload to cached_data. That is what
        // made cost visible on some Azure pages and absent on others at the same moment: only
        // AzureCostService consulted the DB copy, and this page does not use it.
        Optional<List<AzureDashboardData.BillingSummary>> billingSummaryOpt =
            billingIngestionService.readBillingSummary(subscriptionId);

        Optional<AzureDashboardData.CostHistory> costHistoryOpt =
            billingIngestionService.readCostHistory(subscriptionId);

        // --- STEP 1: Get KPIs from Redis Cache ---
        //
        // Every unset figure below stays null and is explained in kpisUnavailableReason. These were
        // all defaulted to 0.0, which reported "no spend, no change" for a subscription whose
        // billing ingestion simply had not run yet.
        List<String> kpiGaps = new ArrayList<>();
        try {
            // Calculate Total Spend
            if (billingSummaryOpt.isPresent()) {
                double totalSpend = billingSummaryOpt.get().stream()
                        .mapToDouble(AzureDashboardData.BillingSummary::getCost)
                        .sum();
                kpis.setTotalSpend(totalSpend);
            } else {
                kpiGaps.add("total spend (billing summary not yet ingested)");
            }

            // Calculate Forecasted Spend from cost history cache
            if (costHistoryOpt.isPresent()) {
                AzureDashboardData.CostHistory costHistory = costHistoryOpt.get();
                List<String> labels = costHistory.getLabels();
                List<Double> costs = costHistory.getCosts();
                List<Boolean> forecastFlags = costHistory.getForecast();

                if (labels != null && costs != null && forecastFlags != null && labels.size() == costs.size()) {

                    // Calculate Forecasted Spend (where anomaly is true)
                    double totalForecast = IntStream.range(0, labels.size())
                        .filter(i -> Boolean.TRUE.equals(forecastFlags.get(i))) // Filter FOR forecast data
                        .mapToDouble(costs::get)
                        .sum();
                    
                    kpis.setForecastedSpend(totalForecast);
                    logger.info("Extracted forecast from cached cost history for {}: ${}", subscriptionId, totalForecast);

                    // Calculate MoM/YoY (where anomaly is false)
                    Map<String, Double> historyMap = IntStream.range(0, labels.size())
                        .filter(i -> !Boolean.TRUE.equals(forecastFlags.get(i))) // Filter OUT forecast data
                        .boxed()
                        .collect(Collectors.toMap(
                            i -> labels.get(i),
                            i -> costs.get(i),
                            (a, b) -> b
                        ));

                    // Calculate MoM change using the two most recent non-forecast entries
                    List<Double> actualCosts = new ArrayList<>();
                    for (int i = labels.size() - 1; i >= 0; i--) {
                        boolean isForecast = i < forecastFlags.size() && Boolean.TRUE.equals(forecastFlags.get(i));
                        if (!isForecast) {
                            actualCosts.add(costs.get(i));
                        }
                        if (actualCosts.size() == 2) break;
                    }
                    if (actualCosts.size() >= 2 && actualCosts.get(1) != 0.0) {
                        double current = actualCosts.get(0);
                        double previous = actualCosts.get(1);
                        kpis.setMomChange(((current - previous) / previous) * 100.0);
                        logger.info("MoM change for {}: {}%", subscriptionId,
                                String.format("%.2f", kpis.getMomChange()));
                    } else {
                        kpiGaps.add("month-over-month change (needs two closed months of history)");
                    }

                    // Real YoY: compare the most recent closed month against the same month a year
                    // earlier. This was hardcoded to 0.0 even on the success path, so the report
                    // always claimed spend was exactly flat year on year.
                    Double yoy = computeYoyChange(labels, costs, forecastFlags);
                    if (yoy != null) {
                        kpis.setYoyChange(yoy);
                    } else {
                        kpiGaps.add("year-over-year change (needs the same month one year earlier)");
                    }
                } else {
                    kpiGaps.add("forecast, month-over-month and year-over-year change "
                            + "(cached cost history is malformed)");
                }
            } else {
                kpiGaps.add("forecast, month-over-month and year-over-year change "
                        + "(cost history not yet ingested)");
            }
        } catch (Exception e) {
            logger.error("Failed to get KPIs from cache for Azure subscription {}: {}", subscriptionId, e.getMessage());
            // Leave every figure null. Zeroing them here turned a cache or parse failure into a
            // confident report of no spend and flat costs.
            kpis.setTotalSpend(null);
            kpis.setForecastedSpend(null);
            kpis.setMomChange(null);
            kpis.setYoyChange(null);
            kpiGaps.clear();
            kpiGaps.add("all KPIs (" + e.getMessage() + ")");
        }
        if (!kpiGaps.isEmpty()) {
            report.setKpisUnavailableReason("Not available: " + String.join("; ", kpiGaps) + ".");
        }
        report.setKpis(kpis);

        // --- STEP 2: Get Cost Breakdown (Cost by Service) ---
        try {
            if (billingSummaryOpt.isPresent()) {
                // Per-service month-over-month, derived from the per-service monthly series the
                // billing ingestion already writes. The previous literal 0.0 meant a service whose
                // spend had doubled still reported no change.
                Map<String, Double> momByService = serviceMonthOverMonthChange(subscriptionId);

                List<AzureFinOpsReportDto.CostBreakdown> breakdownList = billingSummaryOpt.get().stream()
                    .map(item -> new AzureFinOpsReportDto.CostBreakdown(
                        item.getService(),
                        item.getCost(),
                        // Absent rather than zero when the service has no prior closed month.
                        momByService.get(item.getService())
                    ))
                    .collect(Collectors.toList());

                report.setCostBreakdown(breakdownList);
                logger.info("Added {} services to cost breakdown ({} with a month-over-month figure)",
                        breakdownList.size(),
                        breakdownList.stream().filter(b -> b.getChange() != null).count());
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

        // --- STEP 4: Get Rightsizing Recommendations ---
        List<AzureFinOpsReportDto.RightsizingItem> rightsizingItems = new ArrayList<>();
        try {
            AzureRightsizingResponse rightsizingResponse = rightsizingService.getRecommendations(subscriptionId, false);
            if (rightsizingResponse != null && rightsizingResponse.getRecommendations() != null) {
                for (AzureRightsizingRecommendation r : rightsizingResponse.getRecommendations()) {
                    rightsizingItems.add(new AzureFinOpsReportDto.RightsizingItem(
                        r.getResourceName(),
                        r.getResourceType(),
                        r.getCurrentSku(),
                        r.getTargetSku(),
                        r.getMonthlySavings(),
                        r.getAnnualSavings(),
                        r.getImpact(),
                        r.getLocation(),
                        r.isSavingsUnknown()
                    ));
                }
                logger.info("Added {} rightsizing items to FinOps report for {}", rightsizingItems.size(), subscriptionId);
            }
        } catch (Exception e) {
            logger.warn("Failed to get rightsizing data for FinOps report {}: {}", subscriptionId, e.getMessage());
        }
        report.setRightsizingItems(rightsizingItems);

        // --- STEP 5: Get Waste Items ---
        List<AzureFinOpsReportDto.WasteItem> wasteItems = new ArrayList<>();
        try {
            List<AzureWasteItem> wasteRaw = wasteService.getWasteItems(subscriptionId, false);
            if (wasteRaw != null) {
                for (AzureWasteItem w : wasteRaw) {
                    wasteItems.add(new AzureFinOpsReportDto.WasteItem(
                        w.getResourceId(),
                        w.getResourceName(),
                        w.getResourceType(),
                        w.getRegion(),
                        w.getReason(),
                        w.getMonthlySavings(),
                        // Previously dropped here, so ten unpriced or free findings rendered
                        // as definitive $0.00 rows and were summed as though priced.
                        w.isSavingsUnknown(),
                        w.isNoCostImpact()
                    ));
                }
                logger.info("Added {} waste items to FinOps report for {}", wasteItems.size(), subscriptionId);
            }
        } catch (Exception e) {
            logger.warn("Failed to get waste data for FinOps report {}: {}", subscriptionId, e.getMessage());
        }
        report.setWasteItems(wasteItems);

        // --- STEP 6: Calculate Total Potential Savings ---
        // Reconcile overlaps before summing. An under-used VM is reported both as an
        // "Idle Running VM" in the waste list (full compute cost) and as a downsize
        // candidate in the rightsizing list (a delta); adding both double-counted it.
        // Deleting and resizing are mutually exclusive, so the full-cost waste entry wins.
        java.util.Set<String> wasteResourceNames = wasteItems.stream()
                .map(AzureFinOpsReportDto.WasteItem::getResourceName)
                .filter(n -> n != null && !n.isBlank())
                .map(String::toLowerCase)
                .collect(java.util.stream.Collectors.toSet());

        // Only priced, billable findings contribute. Unpriced items and resources Azure does
        // not bill for carry a placeholder zero, so including them added nothing to the total
        // but did make the item count describe a different set from the amount beside it.
        double wasteTotal = wasteItems.stream()
                .filter(AzureFinOpsReportDto.WasteItem::isBillableWaste)
                .mapToDouble(AzureFinOpsReportDto.WasteItem::getMonthlySavings)
                .sum();
        double rightsizingTotal = rightsizingItems.stream()
                .filter(r -> !r.isSavingsUnknown())
                .filter(r -> r.getResourceName() == null
                        || !wasteResourceNames.contains(r.getResourceName().toLowerCase()))
                .mapToDouble(AzureFinOpsReportDto.RightsizingItem::getMonthlySavings)
                .sum();

        double totalPotentialSavings = wasteTotal + rightsizingTotal;
        report.setTotalPotentialSavings(totalPotentialSavings);
        logger.info("Total potential savings for {}: ${} (waste ${} + rightsizing ${} after overlap removal)",
                subscriptionId, totalPotentialSavings, wasteTotal, rightsizingTotal);

        // --- STEP 7: Get Budgets ---
        try {
            List<AzureBudgetDto> budgets = azureBudgetService.getBudgetsSync(subscriptionId);
            report.setBudgets(budgets);
            logger.info("Added {} budgets to FinOps report for {}", budgets.size(), subscriptionId);
        } catch (AzureBudgetService.BudgetsUnavailableException e) {
            // Carried onto the report rather than swallowed. An empty budget list otherwise reads as
            // "no budgets configured", which is what the removed mock data used to hide.
            logger.warn("Budgets unavailable for Azure subscription {}: {}", subscriptionId, e.getMessage());
            report.setBudgets(Collections.emptyList());
            report.setBudgetsUnavailableReason(e.getMessage());
        } catch (Exception e) {
            logger.warn("Failed to get budgets for Azure subscription {}: {}", subscriptionId, e.getMessage());
            report.setBudgets(Collections.emptyList());
            report.setBudgetsUnavailableReason("Budgets could not be read: " + e.getMessage());
        }

        // --- STEP 8: Cost anomalies ---
        try {
            AzureCostAnomalyService.AnomalyOutcome outcome = costAnomalyService.detect(subscriptionId, false);
            report.setCostAnomalies(outcome.anomalies());
            report.setCostAnomaliesUnavailableReason(outcome.unavailableReason());
        } catch (Exception e) {
            logger.warn("Cost anomaly detection failed for Azure subscription {}: {}",
                    subscriptionId, e.getMessage());
            report.setCostAnomalies(Collections.emptyList());
            report.setCostAnomaliesUnavailableReason(
                    "Cost anomaly detection did not run, so an empty list does not mean spend is normal.");
        }

        return report;
    }

    /** Month labels are written by the billing ingestion as e.g. {@code Jan 2026}. */
    private static final DateTimeFormatter MONTH_LABEL =
            DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);

    /**
     * Parses a cost-history month label, tolerating the {@code (Forecast)} suffix.
     *
     * @return the month, or empty when the label is not a month label at all
     */
    private static Optional<YearMonth> parseMonthLabel(String label) {
        if (label == null || label.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(YearMonth.parse(label.replace(" (Forecast)", "").trim(), MONTH_LABEL));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Year-over-year change for the most recent closed month, as a percentage.
     *
     * <p>
     * Compares that month against the same month one year earlier, both taken from the non-forecast
     * entries of the cached history. Returns null — rather than zero — when the earlier month is
     * absent or was itself zero, because "we have no comparison" and "spend did not change" are
     * different answers and only one of them is a measurement.
     */
    // Package-private rather than private so the year-boundary and missing-comparison cases can be
    // asserted directly; the surrounding report method needs Redis and four collaborators.
    Double computeYoyChange(List<String> labels, List<Double> costs, List<Boolean> forecastFlags) {
        if (labels == null || costs == null || labels.size() != costs.size()) {
            return null;
        }

        Map<YearMonth, Double> actualByMonth = new HashMap<>();
        for (int i = 0; i < labels.size(); i++) {
            boolean isForecast = forecastFlags != null && i < forecastFlags.size()
                    && Boolean.TRUE.equals(forecastFlags.get(i));
            if (isForecast) {
                continue;
            }
            Double cost = costs.get(i);
            if (cost == null) {
                continue;
            }
            parseMonthLabel(labels.get(i)).ifPresent(month -> actualByMonth.put(month, cost));
        }

        Optional<YearMonth> latest = actualByMonth.keySet().stream().max(Comparator.naturalOrder());
        if (latest.isEmpty()) {
            return null;
        }

        Double current = actualByMonth.get(latest.get());
        Double yearAgo = actualByMonth.get(latest.get().minusYears(1));
        if (current == null || yearAgo == null || yearAgo == 0.0) {
            return null;
        }
        return ((current - yearAgo) / yearAgo) * 100.0;
    }

    /**
     * Month-over-month change per service, keyed by service name.
     *
     * <p>
     * Reads the per-service monthly series the billing ingestion caches — the same series the
     * service-trend drawer uses — so the breakdown's change column and that chart cannot disagree.
     * A service missing from the result has no prior closed month to compare against and is reported
     * as unknown rather than unchanged.
     */
    private Map<String, Double> serviceMonthOverMonthChange(String subscriptionId) {
        String cacheKey = AzureBillingDataIngestionService.AZURE_SERVICE_MONTHLY_CACHE_PREFIX + subscriptionId;

        Optional<Map<String, Map<String, Double>>> cached =
                redisCacheService.get(cacheKey, new TypeReference<Map<String, Map<String, Double>>>() {});
        if (cached.isEmpty()) {
            logger.info("No per-service monthly series cached for {}; per-service MoM reported as unknown",
                    subscriptionId);
            return Collections.emptyMap();
        }

        Map<String, Double> result = new HashMap<>();
        cached.get().forEach((service, byMonth) -> {
            if (service == null || byMonth == null || byMonth.size() < 2) {
                return;
            }
            // Sort chronologically rather than by label, so "Feb 2026" follows "Jan 2026" instead of
            // sorting alphabetically ahead of it.
            List<YearMonth> months = byMonth.keySet().stream()
                    .map(AzureFinOpsService::parseMonthLabel)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .sorted()
                    .collect(Collectors.toList());
            if (months.size() < 2) {
                return;
            }

            YearMonth currentMonth = months.get(months.size() - 1);
            YearMonth previousMonth = months.get(months.size() - 2);
            Double current = byMonth.get(currentMonth.format(MONTH_LABEL));
            Double previous = byMonth.get(previousMonth.format(MONTH_LABEL));

            if (current != null && previous != null && previous != 0.0) {
                result.put(service, ((current - previous) / previous) * 100.0);
            }
        });
        return result;
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

        FinOpsReportSchedule.Frequency frequency =
                FinOpsReportSchedule.Frequency.valueOf(dto.getFrequency().toUpperCase());
        schedule.setFrequency(frequency);
        schedule.setActive(true);

        // cron_expression is NOT NULL, so leaving this unset did not create a schedule that
        // quietly never fired — it made every Azure FinOps schedule creation fail on insert.
        // Derived through the same helper the AWS path uses so both providers agree.
        schedule.setCronExpression(frequency == FinOpsReportSchedule.Frequency.CUSTOM
                ? com.xammer.cloud.service.FinOpsCron.forCustom(dto)
                : com.xammer.cloud.service.FinOpsCron.forFrequency(frequency.name()));

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

    // --- Cost Explorer date-range endpoints (used by Compare feature) ---

    public List<Map<String, Object>> getBillingSummaryForDateRange(String subscriptionId, String startDate, String endDate) {
        CloudAccount account = findAccountBySubscriptionId(subscriptionId);
        AzureCostQueryService.AzureCostData data = costQueryService.fetchCostBreakdownForDateRange(account, startDate, endDate);
        return data.costByService.entrySet().stream()
                .map(e -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("serviceName", e.getKey());
                    m.put("monthToDateCost", e.getValue());
                    return m;
                })
                .sorted((a, b) -> Double.compare((Double) b.get("monthToDateCost"), (Double) a.get("monthToDateCost")))
                .collect(Collectors.toList());
    }

    public List<Map<String, Object>> getCostByRegionForDateRange(String subscriptionId, String startDate, String endDate) {
        CloudAccount account = findAccountBySubscriptionId(subscriptionId);
        AzureCostQueryService.AzureCostData data = costQueryService.fetchCostBreakdownForDateRange(account, startDate, endDate);
        return data.costByRegion.entrySet().stream()
                .map(e -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("region", e.getKey());
                    m.put("cost", e.getValue());
                    return m;
                })
                .sorted((a, b) -> Double.compare((Double) b.get("cost"), (Double) a.get("cost")))
                .collect(Collectors.toList());
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
