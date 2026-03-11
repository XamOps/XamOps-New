package com.xammer.cloud.service.gcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.bigquery.*;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.DetailedCostDto; // <-- NEW IMPORT
import com.xammer.cloud.dto.gcp.GcpCostDto;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.RedisCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Service
@Slf4j
public class GcpCostService {

    private final GcpClientProvider gcpClientProvider;
    private final RedisCacheService redisCacheService;
    private final CloudAccountRepository cloudAccountRepository;
    private final CachedDataRepository cachedDataRepository;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @Value("${gcp.billing.export-table-name:}")
    private String billingExportTableName;

    // Cache key prefixes
    private static final String COST_BY_TAG_CACHE_PREFIX = "gcp:cost-by-tag:";
    private static final String UNFILTERED_MTD_SPEND_CACHE_PREFIX = "gcp:unfiltered-mtd-spend:";
    private static final String BILLING_SUMMARY_CACHE_PREFIX = "gcp:billing-summary:";
    private static final String HISTORICAL_COSTS_CACHE_PREFIX = "gcp:historical-costs:";
    private static final String COST_BY_REGION_CACHE_PREFIX = "gcp:cost-by-region:";
    private static final String DAILY_COSTS_FORECAST_CACHE_PREFIX = "gcp:daily-costs-forecast:";
    private static final String COST_BREAKDOWN_CACHE_PREFIX = "gcp:cost-breakdown:";
    // --- NEW CACHE KEY ---
    private static final String COST_BY_SERVICE_REGION_CACHE_PREFIX = "gcp:cost-by-service-region:";

    public GcpCostService(GcpClientProvider gcpClientProvider, RedisCacheService redisCacheService,
            CloudAccountRepository cloudAccountRepository,
            CachedDataRepository cachedDataRepository,
            ObjectMapper objectMapper) {
        this.gcpClientProvider = gcpClientProvider;
        this.redisCacheService = redisCacheService;
        this.cloudAccountRepository = cloudAccountRepository;
        this.cachedDataRepository = cachedDataRepository;
        this.objectMapper = objectMapper;
    }

    /** Persist data to DB snapshot, ignoring failures so main flow is never blocked. */
    private void saveToDb(String dbKey, Object data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            CachedData entity = cachedDataRepository.findById(dbKey).orElse(new CachedData());
            entity.setCacheKey(dbKey);
            entity.setJsonData(json);
            entity.setLastUpdated(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
            cachedDataRepository.save(entity);
        } catch (Exception e) {
            log.warn("Failed to save GCP cost DB snapshot for key {}: {}", dbKey, e.getMessage());
        }
    }

    // --- NEW METHOD FOR DETAILED REPORTING ---
    public List<DetailedCostDto> getCostBreakdownByServiceAndRegionSync(String gcpProjectId, LocalDate startDate,
            LocalDate endDate) {
        String cacheKey = COST_BY_SERVICE_REGION_CACHE_PREFIX + gcpProjectId + ":" + startDate + ":" + endDate;

        Optional<List<DetailedCostDto>> cached = redisCacheService.get(
                cacheKey,
                new TypeReference<List<DetailedCostDto>>() {
                });

        if (cached.isPresent()) {
            log.info("✅ Returning cached cost by service/region for project {}", gcpProjectId);
            return cached.get();
        }

        log.info("🔍 Fetching cost by service/region for project {} from {} to {}", gcpProjectId, startDate, endDate);

        Optional<BigQuery> bqOpt = gcpClientProvider.getBigQueryClient(gcpProjectId);
        if (bqOpt.isEmpty()) {
            log.error(
                    "❌ Failed to get BigQuery client for project {}. Check service account credentials and permissions.",
                    gcpProjectId);
            return Collections.emptyList();
        }

        BigQuery bigquery = bqOpt.get();
        Optional<String> tableNameOpt = getBillingTableName(bigquery, gcpProjectId);
        if (tableNameOpt.isEmpty()) {
            log.error("❌ Failed to find billing table for project {}. Ensure BigQuery billing export is configured.",
                    gcpProjectId);
            return Collections.emptyList();
        }

        log.info("✅ Using billing table: {}", tableNameOpt.get());

        String query = String.format(
                "SELECT " +
                        "    service.description as service, " +
                        "    location.region as region, " +
                        "    SUM(cost) as total_cost " +
                        "FROM `%s` " +
                        "WHERE DATE(usage_start_time) >= '%s' AND DATE(usage_start_time) <= '%s' " +
                        "GROUP BY 1, 2 " +
                        "HAVING total_cost > 0.01 " +
                        "ORDER BY total_cost DESC",
                tableNameOpt.get(),
                startDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
                endDate.format(DateTimeFormatter.ISO_LOCAL_DATE));

        try {
            log.info("Executing BigQuery query for project {}: {}", gcpProjectId, query);
            TableResult results = bigquery.query(QueryJobConfiguration.newBuilder(query).build());
            List<DetailedCostDto> resultList = StreamSupport.stream(results.iterateAll().spliterator(), false)
                    .map(row -> new DetailedCostDto(
                            row.get("service").isNull() ? "Uncategorized" : row.get("service").getStringValue(),
                            row.get("region").isNull() ? "Global" : row.get("region").getStringValue(),
                            row.get("total_cost").getDoubleValue()))
                    .collect(Collectors.toList());

            log.info("✅ BigQuery returned {} cost records for project {} (date range: {} to {})",
                    resultList.size(), gcpProjectId, startDate, endDate);
            if (resultList.isEmpty()) {
                log.warn("⚠️ No cost data found for project {} in date range {} to {}. " +
                        "Possible reasons: (1) No usage in this period, (2) Billing export not configured, " +
                        "(3) Billing table has no recent data",
                        gcpProjectId, startDate, endDate);
            }

            redisCacheService.put(cacheKey, resultList, 60 * 6); // Cache for 6 hours
            return resultList;
        } catch (Exception e) {
            log.error("❌ BigQuery query failed for cost by service/region on project {}: {}", gcpProjectId,
                    e.getMessage(), e);
            if (e instanceof InterruptedException)
                Thread.currentThread().interrupt();
            return Collections.emptyList();
        }
    }
    // --- END OF NEW METHOD ---

    // ===== COST BY TAG =====

    public CompletableFuture<List<GcpCostDto>> getCostByTag(String gcpProjectId, String tagKey) {
        return CompletableFuture.supplyAsync(() -> getCostByTagSync(gcpProjectId, tagKey), executor);
    }

    public List<GcpCostDto> getCostByTagSync(String gcpProjectId, String tagKey) {
        String cacheKey = COST_BY_TAG_CACHE_PREFIX + gcpProjectId + ":" + tagKey;

        Optional<List<GcpCostDto>> cached = redisCacheService.get(
                cacheKey,
                new TypeReference<List<GcpCostDto>>() {
                });

        if (cached.isPresent()) {
            log.info("✅ Returning cached cost by tag for project {} / tag {}", gcpProjectId, tagKey);
            return cached.get();
        }

        log.info("🔍 Fetching cost by tag for project {} / tag {}", gcpProjectId, tagKey);

        Optional<BigQuery> bqOpt = gcpClientProvider.getBigQueryClient(gcpProjectId);
        if (bqOpt.isEmpty())
            return Collections.emptyList();

        BigQuery bigquery = bqOpt.get();
        Optional<String> tableNameOpt = getBillingTableName(bigquery, gcpProjectId);
        if (tableNameOpt.isEmpty())
            return Collections.emptyList();

        LocalDate today = LocalDate.now();
        LocalDate startDate = today.withDayOfMonth(1);
        String query = String.format(
                "SELECT l.value as name, SUM(cost) as total_cost, false as is_anomaly " +
                        "FROM `%s`, UNNEST(labels) as l " +
                        "WHERE l.key = '%s' AND DATE(usage_start_time) >= '%s' AND DATE(usage_start_time) <= '%s' " +
                        "GROUP BY 1 ORDER BY total_cost DESC",
                tableNameOpt.get(), tagKey,
                startDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
                today.format(DateTimeFormatter.ISO_LOCAL_DATE));

        List<GcpCostDto> result = executeQuery(bigquery, query, gcpProjectId);
        redisCacheService.put(cacheKey, result, 60);
        return result;
    }

    // ===== UNFILTERED MTD SPEND =====

    public CompletableFuture<Double> getUnfilteredMonthToDateSpend(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> getUnfilteredMonthToDateSpendSync(gcpProjectId), executor);
    }

    public Double getUnfilteredMonthToDateSpendSync(String gcpProjectId) {
        String cacheKey = UNFILTERED_MTD_SPEND_CACHE_PREFIX + gcpProjectId;

        Optional<Double> cached = redisCacheService.get(cacheKey, Double.class);
        if (cached.isPresent()) {
            log.info("✅ Returning cached MTD spend for project {}", gcpProjectId);
            return cached.get();
        }

        log.info("🔍 Fetching MTD spend for project {}", gcpProjectId);

        Optional<BigQuery> bqOpt = gcpClientProvider.getBigQueryClient(gcpProjectId);
        if (bqOpt.isEmpty())
            return 0.0;

        BigQuery bigquery = bqOpt.get();
        Optional<String> tableNameOpt = getBillingTableName(bigquery, gcpProjectId);
        if (tableNameOpt.isEmpty())
            return 0.0;

        LocalDate today = LocalDate.now();
        LocalDate startDate = today.withDayOfMonth(1);
        String query = String.format(
                "SELECT SUM(cost) as total_cost FROM `%s` WHERE DATE(usage_start_time) >= '%s' AND DATE(usage_start_time) <= '%s'",
                tableNameOpt.get(),
                startDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
                today.format(DateTimeFormatter.ISO_LOCAL_DATE));

        try {
            TableResult results = bigquery.query(QueryJobConfiguration.newBuilder(query).build());
            FieldValue value = results.getValues().iterator().next().get("total_cost");
            Double result = value.isNull() ? 0.0 : value.getDoubleValue();
            redisCacheService.put(cacheKey, result, 60);
            saveToDb(cacheKey, result);
            return result;
        } catch (Exception e) {
            log.error("BigQuery query failed for MTD spend on project {}: {}", gcpProjectId, e.getMessage(), e);
            if (e instanceof InterruptedException)
                Thread.currentThread().interrupt();
            return 0.0;
        }
    }

    // ===== BILLING SUMMARY =====

    public CompletableFuture<List<GcpCostDto>> getBillingSummary(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> getBillingSummarySync(gcpProjectId), executor);
    }

    public List<GcpCostDto> getBillingSummarySync(String gcpProjectId) {
        return getBillingSummarySync(gcpProjectId, false);
    }

    public List<GcpCostDto> getBillingSummarySync(String gcpProjectId, boolean forceRefresh) {
        String cacheKey = BILLING_SUMMARY_CACHE_PREFIX + gcpProjectId;

        if (!forceRefresh) {
            Optional<List<GcpCostDto>> cached = redisCacheService.get(
                    cacheKey,
                    new TypeReference<List<GcpCostDto>>() {
                    });

            if (cached.isPresent()) {
                log.info("✅ Returning Redis-cached billing summary for project {}", gcpProjectId);
                return cached.get();
            }

            // Try DB snapshot
            try {
                Optional<CachedData> dbSnapshot = cachedDataRepository.findById(cacheKey);
                if (dbSnapshot.isPresent()) {
                    List<GcpCostDto> dbResult = objectMapper.readValue(dbSnapshot.get().getJsonData(), new TypeReference<>() {});
                    log.info("✅ Returning DB-cached billing summary for project {} — re-warming Redis", gcpProjectId);
                    redisCacheService.put(cacheKey, dbResult, 60);
                    return dbResult;
                }
            } catch (Exception e) {
                log.warn("DB snapshot load failed for billing summary {}: {}", gcpProjectId, e.getMessage());
            }
        } else {
            redisCacheService.evict(cacheKey);
        }

        log.info("🔍 Fetching billing summary for project {}", gcpProjectId);

        Optional<BigQuery> bqOpt = gcpClientProvider.getBigQueryClient(gcpProjectId);
        if (bqOpt.isEmpty())
            return Collections.emptyList();

        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.withDayOfMonth(1);

        List<GcpCostDto> result = queryCostData(bqOpt.get(), gcpProjectId, "service.description", startDate, endDate);
        redisCacheService.put(cacheKey, result, 60);
        saveToDb(cacheKey, result);
        return result;
    }

    // ===== HISTORICAL COSTS =====

    public CompletableFuture<List<GcpCostDto>> getHistoricalCosts(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> getHistoricalCostsSync(gcpProjectId), executor);
    }

    public List<GcpCostDto> getHistoricalCostsSync(String gcpProjectId) {
        return getHistoricalCostsSync(gcpProjectId, false);
    }

    public List<GcpCostDto> getHistoricalCostsSync(String gcpProjectId, boolean forceRefresh) {
        String cacheKey = HISTORICAL_COSTS_CACHE_PREFIX + gcpProjectId;

        if (!forceRefresh) {
            Optional<List<GcpCostDto>> cached = redisCacheService.get(
                    cacheKey,
                    new TypeReference<List<GcpCostDto>>() {
                    });

            if (cached.isPresent()) {
                log.info("✅ Returning cached historical costs for project {}", gcpProjectId);
                return cached.get();
            }

            // Try DB snapshot
            try {
                Optional<CachedData> dbSnapshot = cachedDataRepository.findById(cacheKey);
                if (dbSnapshot.isPresent()) {
                    List<GcpCostDto> dbResult = objectMapper.readValue(dbSnapshot.get().getJsonData(), new TypeReference<>() {});
                    log.info("✅ Returning DB-cached historical costs for project {} — re-warming Redis", gcpProjectId);
                    redisCacheService.put(cacheKey, dbResult, 60);
                    return dbResult;
                }
            } catch (Exception e) {
                log.warn("DB snapshot load failed for historical costs {}: {}", gcpProjectId, e.getMessage());
            }
        } else {
            redisCacheService.evict(cacheKey);
        }

        log.info("🔍 Fetching historical costs for project {}", gcpProjectId);

        Optional<BigQuery> bqOpt = gcpClientProvider.getBigQueryClient(gcpProjectId);
        if (bqOpt.isEmpty())
            return Collections.emptyList();

        BigQuery bigquery = bqOpt.get();
        Optional<String> tableNameOpt = getBillingTableName(bigquery, gcpProjectId);
        if (tableNameOpt.isEmpty())
            return Collections.emptyList();

        LocalDate today = LocalDate.now();
        String endDate = today.format(DateTimeFormatter.ISO_LOCAL_DATE);
        String startDate = today.minusMonths(6).withDayOfMonth(1).format(DateTimeFormatter.ISO_LOCAL_DATE);

        String query = String.format(
                "WITH MonthlyCosts AS (SELECT FORMAT_DATE('%%Y-%%m', DATE(usage_start_time)) as name, SUM(cost) as total_cost FROM `%s` WHERE DATE(usage_start_time) >= '%s' AND DATE(usage_start_time) <= '%s' GROUP BY 1), "
                        +
                        "MonthlyCostsWithLag AS (SELECT name, total_cost, LAG(total_cost, 1, 0) OVER (ORDER BY name) as prev_month_cost FROM MonthlyCosts) "
                        +
                        "SELECT name, total_cost, (total_cost > prev_month_cost * 1.2 AND prev_month_cost > 10) as is_anomaly FROM MonthlyCostsWithLag ORDER BY name",
                tableNameOpt.get(), startDate, endDate);

        List<GcpCostDto> result = executeQuery(bigquery, query, gcpProjectId);
        redisCacheService.put(cacheKey, result, 60);
        saveToDb(cacheKey, result);
        return result;
    }

    // ===== COST BY REGION =====

    public CompletableFuture<List<GcpCostDto>> getCostByRegion(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> getCostByRegionSync(gcpProjectId), executor);
    }

    public List<GcpCostDto> getCostByRegionSync(String gcpProjectId) {
        return getCostByRegionSync(gcpProjectId, false);
    }

    public List<GcpCostDto> getCostByRegionSync(String gcpProjectId, boolean forceRefresh) {
        String cacheKey = COST_BY_REGION_CACHE_PREFIX + gcpProjectId;

        if (!forceRefresh) {
            Optional<List<GcpCostDto>> cached = redisCacheService.get(
                    cacheKey,
                    new TypeReference<List<GcpCostDto>>() {
                    });

            if (cached.isPresent()) {
                log.info("✅ Returning Redis-cached cost by region for project {}", gcpProjectId);
                return cached.get();
            }

            // Try DB snapshot
            try {
                Optional<CachedData> dbSnapshot = cachedDataRepository.findById(cacheKey);
                if (dbSnapshot.isPresent()) {
                    List<GcpCostDto> dbResult = objectMapper.readValue(dbSnapshot.get().getJsonData(), new TypeReference<>() {});
                    log.info("✅ Returning DB-cached cost by region for project {} — re-warming Redis", gcpProjectId);
                    redisCacheService.put(cacheKey, dbResult, 60);
                    return dbResult;
                }
            } catch (Exception e) {
                log.warn("DB snapshot load failed for cost by region {}: {}", gcpProjectId, e.getMessage());
            }
        } else {
            redisCacheService.evict(cacheKey);
        }

        log.info("🔍 Fetching cost by region for project {}", gcpProjectId);

        Optional<BigQuery> bqOpt = gcpClientProvider.getBigQueryClient(gcpProjectId);
        if (bqOpt.isEmpty())
            return Collections.emptyList();

        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.withDayOfMonth(1);

        List<GcpCostDto> result = queryCostData(bqOpt.get(), gcpProjectId, "location.region", startDate, endDate);
        redisCacheService.put(cacheKey, result, 60);
        saveToDb(cacheKey, result);
        return result;
    }

    // ===== DAILY COSTS FOR FORECAST =====

    public CompletableFuture<List<Map<String, Object>>> getDailyCostsForForecast(String gcpProjectId,
            String serviceName, int days) {
        return CompletableFuture.supplyAsync(() -> getDailyCostsForForecastSync(gcpProjectId, serviceName, days),
                executor);
    }

    public List<Map<String, Object>> getDailyCostsForForecastSync(String gcpProjectId, String serviceName, int days) {
        String cacheKey = DAILY_COSTS_FORECAST_CACHE_PREFIX + gcpProjectId + ":" + serviceName + ":" + days;

        Optional<List<Map<String, Object>>> cached = redisCacheService.get(
                cacheKey,
                new TypeReference<List<Map<String, Object>>>() {
                });

        if (cached.isPresent()) {
            log.info("✅ Returning cached daily costs forecast for project {}", gcpProjectId);
            return cached.get();
        }

        log.info("🔍 Fetching daily costs forecast for project {}", gcpProjectId);

        Optional<BigQuery> bqOpt = gcpClientProvider.getBigQueryClient(gcpProjectId);
        if (bqOpt.isEmpty())
            return Collections.emptyList();

        BigQuery bigquery = bqOpt.get();
        Optional<String> tableNameOpt = getBillingTableName(bigquery, gcpProjectId);
        if (tableNameOpt.isEmpty())
            return Collections.emptyList();

        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(days);

        String serviceFilter = (serviceName != null && !serviceName.isEmpty() && !"ALL".equalsIgnoreCase(serviceName))
                ? String.format("AND service.description = '%s'", serviceName)
                : "";

        String query = String.format(
                "SELECT FORMAT_DATE('%%Y-%%m-%%d', DATE(usage_start_time)) as date, SUM(cost) as cost FROM `%s` WHERE DATE(usage_start_time) >= '%s' AND DATE(usage_start_time) < '%s' %s GROUP BY 1 ORDER BY 1",
                tableNameOpt.get(),
                startDate.toString(),
                endDate.toString(),
                serviceFilter);

        try {
            TableResult results = bigquery.query(QueryJobConfiguration.newBuilder(query).build());
            List<Map<String, Object>> result = StreamSupport.stream(results.iterateAll().spliterator(), false)
                    .map(row -> Map.<String, Object>of("date", row.get("date").getStringValue(), "cost",
                            row.get("cost").getDoubleValue()))
                    .collect(Collectors.toList());
            redisCacheService.put(cacheKey, result, 60);
            return result;
        } catch (Exception e) {
            log.error("Error fetching daily GCP costs for forecast", e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Collections.emptyList();
        }
    }

    // ===== COST BREAKDOWN =====

    public CompletableFuture<List<GcpCostDto>> getCostBreakdown(String gcpProjectId, String groupBy) {
        return CompletableFuture.supplyAsync(() -> getCostBreakdownSync(gcpProjectId, groupBy), executor);
    }

    public List<GcpCostDto> getCostBreakdownSync(String gcpProjectId, String groupBy) {
        String cacheKey = COST_BREAKDOWN_CACHE_PREFIX + gcpProjectId + ":" + groupBy;

        Optional<List<GcpCostDto>> cached = redisCacheService.get(
                cacheKey,
                new TypeReference<List<GcpCostDto>>() {
                });

        if (cached.isPresent()) {
            log.info("✅ Returning Redis-cached cost breakdown for project {} / groupBy {}", gcpProjectId, groupBy);
            return cached.get();
        }

        // Try DB snapshot
        try {
            Optional<CachedData> dbSnapshot = cachedDataRepository.findById(cacheKey);
            if (dbSnapshot.isPresent()) {
                List<GcpCostDto> dbResult = objectMapper.readValue(dbSnapshot.get().getJsonData(), new TypeReference<>() {});
                log.info("✅ Returning DB-cached cost breakdown for project {} / groupBy {} — re-warming Redis", gcpProjectId, groupBy);
                redisCacheService.put(cacheKey, dbResult, 60);
                return dbResult;
            }
        } catch (Exception e) {
            log.warn("DB snapshot load failed for cost breakdown {}: {}", gcpProjectId, e.getMessage());
        }

        log.info("🔍 Fetching cost breakdown for project {} / groupBy {}", gcpProjectId, groupBy);

        Optional<BigQuery> bqOpt = gcpClientProvider.getBigQueryClient(gcpProjectId);
        if (bqOpt.isEmpty())
            return Collections.emptyList();

        String dimension = "service.description";
        if ("REGION".equalsIgnoreCase(groupBy)) {
            dimension = "location.region";
        } else if ("PROJECT".equalsIgnoreCase(groupBy)) {
            dimension = "project.name";
        }

        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.withDayOfMonth(1);

        List<GcpCostDto> result = queryCostData(bqOpt.get(), gcpProjectId, dimension, startDate, endDate);
        redisCacheService.put(cacheKey, result, 60);
        saveToDb(cacheKey, result);
        return result;
    }

    // ===== UTILITY METHODS =====

    public double calculateForecastedSpend(double monthToDateSpend) {
        LocalDate today = LocalDate.now();
        int daysInMonth = today.lengthOfMonth();
        int currentDay = today.getDayOfMonth();
        if (currentDay > 0) {
            return (monthToDateSpend / currentDay) * daysInMonth;
        }
        return 0.0;
    }

    public CompletableFuture<Double> getLastMonthSpend(String gcpProjectId) {
        return getHistoricalCosts(gcpProjectId).thenApply(historicalCosts -> {
            String lastMonthStr = LocalDate.now().minusMonths(1).format(DateTimeFormatter.ofPattern("yyyy-MM"));
            return historicalCosts.stream()
                    .filter(c -> c.getName().equals(lastMonthStr))
                    .mapToDouble(GcpCostDto::getAmount)
                    .findFirst()
                    .orElse(0.0);
        });
    }

    private List<GcpCostDto> queryCostData(BigQuery bigquery, String gcpProjectId, String dimension,
            LocalDate startDate, LocalDate endDate) {
        Optional<String> tableNameOpt = getBillingTableName(bigquery, gcpProjectId);
        if (tableNameOpt.isEmpty())
            return Collections.emptyList();

        String query = String.format(
                "SELECT COALESCE(%s, 'Uncategorized') as name, SUM(cost) as total_cost, false as is_anomaly FROM `%s` WHERE DATE(usage_start_time) >= '%s' AND DATE(usage_start_time) <= '%s' GROUP BY 1 HAVING total_cost > 0 ORDER BY total_cost DESC",
                dimension, tableNameOpt.get(), startDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
                endDate.format(DateTimeFormatter.ISO_LOCAL_DATE));
        return executeQuery(bigquery, query, gcpProjectId);
    }

    private List<GcpCostDto> executeQuery(BigQuery bigquery, String query, String gcpProjectId) {
        try {
            log.info("Executing BigQuery query for project {}: {}", gcpProjectId, query);
            TableResult results = bigquery.query(QueryJobConfiguration.newBuilder(query).build());
            return StreamSupport.stream(results.iterateAll().spliterator(), false)
                    .map(row -> new GcpCostDto(
                            row.get("name").getStringValue(),
                            row.get("total_cost").getDoubleValue(),
                            row.get("is_anomaly").getBooleanValue()))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("BigQuery query failed for project {}: {}", gcpProjectId, e.getMessage(), e);
            if (e instanceof InterruptedException)
                Thread.currentThread().interrupt();
            return Collections.emptyList();
        }
    }

    private Optional<String> getBillingTableName(BigQuery bigquery, String gcpProjectId) {
        // Priority 1: Check billing table stored in CloudAccount
        try {
            Optional<CloudAccount> accountOpt = cloudAccountRepository.findByGcpProjectId(gcpProjectId);
            if (accountOpt.isPresent()) {
                String storedTable = accountOpt.get().getBillingExportTable();
                if (storedTable != null && !storedTable.isBlank()) {
                    if (storedTable.chars().filter(c -> c == '.').count() == 2) {
                        log.info("Using billing table from CloudAccount for project {}: {}", gcpProjectId, storedTable);
                        return Optional.of(storedTable);
                    } else {
                        log.warn("Stored billing table '{}' for project {} is not fully qualified (expected project.dataset.table). Falling back to discovery.", storedTable, gcpProjectId);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to lookup billing table from CloudAccount for project {}: {}", gcpProjectId, e.getMessage());
        }

        // Priority 2: Check application property
        if (billingExportTableName != null && !billingExportTableName.isBlank())
            return Optional.of(billingExportTableName);

        // Priority 3: Dynamic discovery from BigQuery datasets
        try {
            for (Dataset dataset : bigquery.listDatasets(gcpProjectId).iterateAll()) {
                for (Table table : bigquery.listTables(dataset.getDatasetId()).iterateAll()) {
                    String tableId = table.getTableId().getTable();
                    if (tableId.startsWith("gcp_billing_export_v1")
                            || tableId.startsWith("gcp_billing_export_resource_v1")) {
                        String fullName = String.format("%s.%s.%s",
                                table.getTableId().getProject(),
                                table.getTableId().getDataset(),
                                tableId);
                        log.info("Dynamically found billing table: {}", fullName);
                        return Optional.of(fullName);
                    }
                }

            }
        } catch (Exception e) {
            log.error("Failed to discover BigQuery billing table for project {}: {}", gcpProjectId, e.getMessage());
        }

        log.warn(
                "Could not find a GCP billing export table for project {}. Please configure gcp.billing.export-table-name.",
                gcpProjectId);
        return Optional.empty();
    }

    // ===== DATE RANGE FILTERING METHODS =====

    /**
     * Get billing summary (cost by service) with optional date range filtering.
     */
    public CompletableFuture<List<GcpCostDto>> getBillingSummaryWithDateRange(String gcpProjectId, String startDate,
            String endDate) {
        return CompletableFuture.supplyAsync(() -> {
            LocalDate start = startDate != null ? LocalDate.parse(startDate) : LocalDate.now().withDayOfMonth(1);
            LocalDate end = endDate != null ? LocalDate.parse(endDate) : LocalDate.now();

            String cacheKey = BILLING_SUMMARY_CACHE_PREFIX + gcpProjectId + ":" + start + ":" + end;

            Optional<List<GcpCostDto>> cached = redisCacheService.get(cacheKey, new TypeReference<List<GcpCostDto>>() {
            });
            if (cached.isPresent()) {
                log.info("Returning cached billing summary for project {} (dates: {} to {})", gcpProjectId, start, end);
                return cached.get();
            }

            log.info("Fetching billing summary for project {} from {} to {}", gcpProjectId, start, end);

            Optional<BigQuery> bqOpt = gcpClientProvider.getBigQueryClient(gcpProjectId);
            if (bqOpt.isEmpty())
                return Collections.emptyList();

            List<GcpCostDto> result = queryCostData(bqOpt.get(), gcpProjectId, "service.description", start, end);
            redisCacheService.put(cacheKey, result, 10);
            return result;
        }, executor);
    }

    /**
     * Get cost by region with optional date range filtering.
     */
    public CompletableFuture<List<GcpCostDto>> getCostByRegionWithDateRange(String gcpProjectId, String startDate,
            String endDate) {
        return CompletableFuture.supplyAsync(() -> {
            LocalDate start = startDate != null ? LocalDate.parse(startDate) : LocalDate.now().withDayOfMonth(1);
            LocalDate end = endDate != null ? LocalDate.parse(endDate) : LocalDate.now();

            String cacheKey = COST_BY_REGION_CACHE_PREFIX + gcpProjectId + ":" + start + ":" + end;

            Optional<List<GcpCostDto>> cached = redisCacheService.get(cacheKey, new TypeReference<List<GcpCostDto>>() {
            });
            if (cached.isPresent()) {
                log.info("Returning cached cost by region for project {} (dates: {} to {})", gcpProjectId, start, end);
                return cached.get();
            }

            log.info("Fetching cost by region for project {} from {} to {}", gcpProjectId, start, end);

            Optional<BigQuery> bqOpt = gcpClientProvider.getBigQueryClient(gcpProjectId);
            if (bqOpt.isEmpty())
                return Collections.emptyList();

            List<GcpCostDto> result = queryCostData(bqOpt.get(), gcpProjectId, "location.region", start, end);
            redisCacheService.put(cacheKey, result, 10);
            return result;
        }, executor);
    }

    /**
     * Get cost by label (tag) with optional date range filtering.
     */
    public CompletableFuture<List<Map<String, Object>>> getCostByLabel(String gcpProjectId, String labelKey,
            String startDate, String endDate) {
        return CompletableFuture.supplyAsync(() -> {
            LocalDate start = startDate != null ? LocalDate.parse(startDate) : LocalDate.now().withDayOfMonth(1);
            LocalDate end = endDate != null ? LocalDate.parse(endDate) : LocalDate.now();

            String cacheKey = COST_BY_TAG_CACHE_PREFIX + gcpProjectId + ":" + labelKey + ":" + start + ":" + end;

            Optional<List<Map<String, Object>>> cached = redisCacheService.get(cacheKey,
                    new TypeReference<List<Map<String, Object>>>() {
                    });
            if (cached.isPresent()) {
                log.info("Returning cached cost by label for project {} / label {} (dates: {} to {})",
                        gcpProjectId, labelKey, start, end);
                return cached.get();
            }

            log.info("Fetching cost by label '{}' for project {} from {} to {}", labelKey, gcpProjectId, start, end);

            Optional<BigQuery> bqOpt = gcpClientProvider.getBigQueryClient(gcpProjectId);
            if (bqOpt.isEmpty())
                return Collections.emptyList();

            BigQuery bigquery = bqOpt.get();
            Optional<String> tableNameOpt = getBillingTableName(bigquery, gcpProjectId);
            if (tableNameOpt.isEmpty())
                return Collections.emptyList();

            String query = String.format(
                    "SELECT COALESCE(l.value, 'Untagged') as labelValue, SUM(cost) as cost " +
                            "FROM `%s`, UNNEST(labels) as l " +
                            "WHERE l.key = '%s' AND DATE(usage_start_time) >= '%s' AND DATE(usage_start_time) <= '%s' "
                            +
                            "GROUP BY 1 HAVING cost > 0.01 ORDER BY cost DESC",
                    tableNameOpt.get(), labelKey,
                    start.format(DateTimeFormatter.ISO_LOCAL_DATE),
                    end.format(DateTimeFormatter.ISO_LOCAL_DATE));

            try {
                TableResult results = bigquery.query(QueryJobConfiguration.newBuilder(query).build());
                List<Map<String, Object>> result = StreamSupport.stream(results.iterateAll().spliterator(), false)
                        .map(row -> Map.<String, Object>of(
                                "labelValue", row.get("labelValue").getStringValue(),
                                "cost", row.get("cost").getDoubleValue()))
                        .collect(Collectors.toList());
                redisCacheService.put(cacheKey, result, 10);
                return result;
            } catch (Exception e) {
                log.error("BigQuery query failed for cost by label on project {}: {}", gcpProjectId, e.getMessage(), e);
                if (e instanceof InterruptedException)
                    Thread.currentThread().interrupt();
                return Collections.emptyList();
            }
        }, executor);
    }

    // ===== COST ANOMALY DETECTION =====

    private static final String COST_ANOMALIES_CACHE_PREFIX = "gcp:cost-anomalies:";

    /**
     * Detect cost anomalies for a GCP project based on historical spending
     * patterns.
     * An anomaly is detected when spending increases by more than 20% compared to
     * the previous period.
     */
    public CompletableFuture<List<com.xammer.cloud.dto.DashboardData.CostAnomaly>> getCostAnomalies(
            String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> getCostAnomaliesSync(gcpProjectId), executor);
    }

    public List<com.xammer.cloud.dto.DashboardData.CostAnomaly> getCostAnomaliesSync(String gcpProjectId) {
        String cacheKey = COST_ANOMALIES_CACHE_PREFIX + gcpProjectId;

        Optional<List<com.xammer.cloud.dto.DashboardData.CostAnomaly>> cached = redisCacheService.get(
                cacheKey,
                new TypeReference<List<com.xammer.cloud.dto.DashboardData.CostAnomaly>>() {
                });

        if (cached.isPresent()) {
            log.info("Returning cached cost anomalies for project {}", gcpProjectId);
            return cached.get();
        }

        log.info("Detecting cost anomalies for project {}", gcpProjectId);

        Optional<BigQuery> bqOpt = gcpClientProvider.getBigQueryClient(gcpProjectId);
        if (bqOpt.isEmpty())
            return Collections.emptyList();

        BigQuery bigquery = bqOpt.get();
        Optional<String> tableNameOpt = getBillingTableName(bigquery, gcpProjectId);
        if (tableNameOpt.isEmpty())
            return Collections.emptyList();

        LocalDate today = LocalDate.now();
        LocalDate startDate = today.minusDays(60);

        // Query to detect anomalies: daily costs with significant spikes
        String query = String.format(
                "WITH DailyCosts AS (" +
                        "  SELECT DATE(usage_start_time) as cost_date, " +
                        "         service.description as service_name, " +
                        "         SUM(cost) as daily_cost " +
                        "  FROM `%s` " +
                        "  WHERE DATE(usage_start_time) >= '%s' AND DATE(usage_start_time) <= '%s' " +
                        "  GROUP BY 1, 2 " +
                        "), " +
                        "CostsWithLag AS (" +
                        "  SELECT cost_date, service_name, daily_cost, " +
                        "         AVG(daily_cost) OVER (PARTITION BY service_name ORDER BY cost_date ROWS BETWEEN 7 PRECEDING AND 1 PRECEDING) as avg_prev_7_days "
                        +
                        "  FROM DailyCosts " +
                        ") " +
                        "SELECT service_name, cost_date, daily_cost, avg_prev_7_days, " +
                        "       SAFE_DIVIDE(daily_cost - avg_prev_7_days, avg_prev_7_days) * 100 as change_percent " +
                        "FROM CostsWithLag " +
                        "WHERE avg_prev_7_days > 10 AND daily_cost > avg_prev_7_days * 1.5 " +
                        "ORDER BY change_percent DESC " +
                        "LIMIT 20",
                tableNameOpt.get(),
                startDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
                today.format(DateTimeFormatter.ISO_LOCAL_DATE));

        try {
            TableResult results = bigquery.query(QueryJobConfiguration.newBuilder(query).build());
            List<com.xammer.cloud.dto.DashboardData.CostAnomaly> anomalies = new ArrayList<>();
            int anomalyCounter = 1;

            for (FieldValueList row : results.iterateAll()) {
                String serviceName = row.get("service_name").isNull() ? "Unknown"
                        : row.get("service_name").getStringValue();
                LocalDate costDate = LocalDate.parse(row.get("cost_date").getStringValue());
                double unexpectedSpend = row.get("daily_cost").getDoubleValue() -
                        (row.get("avg_prev_7_days").isNull() ? 0 : row.get("avg_prev_7_days").getDoubleValue());

                anomalies.add(new com.xammer.cloud.dto.DashboardData.CostAnomaly(
                        "GCP-ANOMALY-" + gcpProjectId.hashCode() + "-" + anomalyCounter++,
                        serviceName,
                        unexpectedSpend,
                        costDate,
                        costDate));
            }

            redisCacheService.put(cacheKey, anomalies, 10);
            log.info("Found {} cost anomalies for project {}", anomalies.size(), gcpProjectId);
            return anomalies;

        } catch (Exception e) {
            log.error("Failed to detect cost anomalies for project {}: {}", gcpProjectId, e.getMessage(), e);
            if (e instanceof InterruptedException)
                Thread.currentThread().interrupt();
            return Collections.emptyList();
        }
    }

    // ===== CACHE MANAGEMENT =====

    /**
     * Clear all cost caches for a specific project
     */
    public void clearCostCacheForProject(String gcpProjectId) {
        redisCacheService.evict(COST_BY_TAG_CACHE_PREFIX + gcpProjectId);
        redisCacheService.evict(UNFILTERED_MTD_SPEND_CACHE_PREFIX + gcpProjectId);
        redisCacheService.evict(BILLING_SUMMARY_CACHE_PREFIX + gcpProjectId);
        redisCacheService.evict(HISTORICAL_COSTS_CACHE_PREFIX + gcpProjectId);
        redisCacheService.evict(COST_BY_REGION_CACHE_PREFIX + gcpProjectId);
        redisCacheService.evict(COST_ANOMALIES_CACHE_PREFIX + gcpProjectId);
        log.info("Cleared cost cache for project {}", gcpProjectId);
    }
}