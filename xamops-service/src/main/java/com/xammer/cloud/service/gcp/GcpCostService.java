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
import java.time.YearMonth;
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
    private final GcpCostDataSnapshotService gcpCostDataSnapshotService;
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
    private static final String CURRENCY_CACHE_PREFIX = "gcp:currency:";

    // DB snapshot key prefixes for section-level cache (permanent, survives Redis restarts)
    private static final String GCP_FINOPS_BILLING_SVC_DB  = "GCP_FINOPS_BILLING_SVC::";
    private static final String GCP_FINOPS_COST_REGION_DB  = "GCP_FINOPS_COST_REGION::";
    private static final String GCP_FINOPS_COST_LABEL_DB   = "GCP_FINOPS_COST_LABEL::";
    private static final String GCP_FINOPS_ANOMALIES_DB    = "GCP_FINOPS_ANOMALIES::";

    // DB snapshot key prefixes for cost-page methods
    private static final String GCP_COST_BREAKDOWN_DB      = "GCP_COST_BREAKDOWN::";
    private static final String GCP_COST_BREAKDOWN_DATE_DB = "GCP_COST_BREAKDOWN_DATE::";
    private static final String GCP_COST_HISTORY_DB        = "GCP_COST_HISTORY::";
    private static final String GCP_COST_BY_REGION_DB      = "GCP_COST_BY_REGION::";
    private static final String GCP_COST_DAILY_FORECAST_DB = "GCP_COST_DAILY_FORECAST::";
    private static final String GCP_COST_BY_TAG_DB         = "GCP_COST_BY_TAG::";
    private static final String GCP_COST_SVC_REGION_DB     = "GCP_COST_SVC_REGION::";

    private final CspBillingQueryService cspBillingQueryService;
    private final GcpBillingStatusService gcpBillingStatusService;

    public GcpCostService(GcpClientProvider gcpClientProvider, RedisCacheService redisCacheService,
            CloudAccountRepository cloudAccountRepository,
            CachedDataRepository cachedDataRepository,
            ObjectMapper objectMapper,
            GcpCostDataSnapshotService gcpCostDataSnapshotService,
            CspBillingQueryService cspBillingQueryService,
            GcpBillingStatusService gcpBillingStatusService) {
        this.gcpClientProvider = gcpClientProvider;
        this.redisCacheService = redisCacheService;
        this.cloudAccountRepository = cloudAccountRepository;
        this.cachedDataRepository = cachedDataRepository;
        this.objectMapper = objectMapper;
        this.gcpCostDataSnapshotService = gcpCostDataSnapshotService;
        this.cspBillingQueryService = cspBillingQueryService;
        this.gcpBillingStatusService = gcpBillingStatusService;
    }

    // =====================================================================================
    // CSP (reseller) billing support
    //
    // A CSP-managed project has NO billing export of its own — its costs live in the
    // reseller's dataset under a flattened schema (project_id, cost_at_list,
    // service_description, region, ...). The standard-export SQL in this class
    // (SUM(cost), project.id, service.description, location.region) cannot read it, and
    // getBillingTableName() cannot find a table for it, so every query returned empty.
    // These helpers let the cost getters read the reseller dataset directly instead of
    // depending on the nightly ingestion having pre-warmed exactly the right cache key.
    // =====================================================================================

    /** A CSP-managed project's reseller config plus a BigQuery client authorised for it. */
    private static final class CspSource {
        final com.xammer.cloud.domain.CspBillingConfig config;
        final BigQuery client;
        CspSource(com.xammer.cloud.domain.CspBillingConfig config, BigQuery client) {
            this.config = config;
            this.client = client;
        }
    }

    /** Present only when the project is reseller-billed and its config resolves. */
    private Optional<CspSource> cspSource(String gcpProjectId) {
        try {
            return cloudAccountRepository.findCspBillingConfigByGcpProjectId(gcpProjectId)
                    .map(config -> new CspSource(config, cspBillingQueryService.buildClient(config)));
        } catch (Exception e) {
            log.error("Failed to resolve CSP billing source for project {}: {}", gcpProjectId, e.getMessage());
            return Optional.empty();
        }
    }

    /** Maps a standard-export dimension expression to its flattened reseller column. */
    private static String cspDimension(String standardDimension) {
        if ("location.region".equals(standardDimension)) return "region";
        if ("project.name".equals(standardDimension))    return "project_name";
        return "service_description";
    }

    /**
     * Grouped cost for a date range, from whichever billing source the project actually has:
     * the reseller dataset for CSP-managed projects, the project's own billing export
     * otherwise. Single entry point so every caller gets CSP support automatically.
     */
    private List<GcpCostDto> fetchCostData(String gcpProjectId, String dimension,
                                           LocalDate startDate, LocalDate endDate) {
        Optional<CspSource> csp = cspSource(gcpProjectId);
        if (csp.isPresent()) {
            log.info("🏷️ CSP-managed project {} — reading {} from reseller dataset ({} to {})",
                    gcpProjectId, cspDimension(dimension), startDate, endDate);
            return cspBillingQueryService.queryBreakdownByDateRange(
                    csp.get().client, csp.get().config, gcpProjectId,
                    cspDimension(dimension), startDate.toString(), endDate.toString());
        }

        Optional<BigQuery> bqOpt = gcpClientProvider.getBigQueryClient(gcpProjectId);
        if (bqOpt.isEmpty()) {
            return Collections.emptyList();
        }
        return queryCostData(bqOpt.get(), gcpProjectId, dimension, startDate, endDate);
    }

    /** Redis TTL (minutes) for section data that actually has rows. */
    private static final int SECTION_TTL_MINUTES = 60 * 24;

    /**
     * Redis TTL (minutes) for an empty section result. Deliberately short: an empty list is
     * almost always a failed fetch (bad credentials, missing IAM permission, timeout), and
     * the old code cached it for 24 hours AND wrote a permanent DB snapshot. Because the DB
     * read path treated any present snapshot as a hit, that empty result was then served
     * forever and the project never retried — so the very error you needed to see never got
     * logged again.
     */
    private static final int EMPTY_SECTION_TTL_MINUTES = 5;

    /** True when cached data holds usable rows. */
    private static boolean hasRows(java.util.Collection<?> data) {
        return data != null && !data.isEmpty();
    }

    /**
     * Cache a section result, only snapshotting it when it has rows, so a transient failure
     * cannot become permanent truth.
     */
    private void cacheCostData(String cacheKey, String dbKey, java.util.Collection<?> result,
                               String label, String gcpProjectId) {
        if (hasRows(result)) {
            redisCacheService.put(cacheKey, result, SECTION_TTL_MINUTES);
            saveToDb(dbKey, result);
            return;
        }
        redisCacheService.put(cacheKey, result == null ? Collections.emptyList() : result,
                EMPTY_SECTION_TTL_MINUTES);
        log.warn("⚠️ Empty {} for project {} — caching for {} min only and skipping the DB snapshot, "
                        + "so the next request retries instead of serving this forever",
                label, gcpProjectId, EMPTY_SECTION_TTL_MINUTES);
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

    // --- DETAILED REPORTING (service × region breakdown) ---
    public List<DetailedCostDto> getCostBreakdownByServiceAndRegionSync(String gcpProjectId, LocalDate startDate,
            LocalDate endDate) {
        return getCostBreakdownByServiceAndRegionSync(gcpProjectId, startDate, endDate, false);
    }

    public List<DetailedCostDto> getCostBreakdownByServiceAndRegionSync(String gcpProjectId, LocalDate startDate,
            LocalDate endDate, boolean forceRefresh) {
        String cacheKey = COST_BY_SERVICE_REGION_CACHE_PREFIX + gcpProjectId + ":" + startDate + ":" + endDate;
        String dbKey    = GCP_COST_SVC_REGION_DB + gcpProjectId + "::" + startDate + "::" + endDate;

        if (!forceRefresh) {
            Optional<List<DetailedCostDto>> cached = redisCacheService.get(
                    cacheKey,
                    new TypeReference<List<DetailedCostDto>>() {
                    });

            if (cached.isPresent()) {
                log.info("✅ Returning cached cost by service/region for project {}", gcpProjectId);
                return cached.get();
            }

            try {
                Optional<CachedData> dbSnap = cachedDataRepository.findById(dbKey);
                if (dbSnap.isPresent()) {
                    List<DetailedCostDto> dbData = objectMapper.readValue(dbSnap.get().getJsonData(), new TypeReference<List<DetailedCostDto>>() {});
                    if (dbData != null) {
                        redisCacheService.put(cacheKey, dbData, 60 * 24);
                        log.info("✅ DB hit — re-warmed Redis: {}", dbKey);
                        return dbData;
                    }
                }
            } catch (Exception e) { log.warn("DB cache miss for {}: {}", dbKey, e.getMessage()); }
        }

        log.info("🔍 Fetching cost by service/region for project {} from {} to {}", gcpProjectId, startDate, endDate);

        Optional<BigQuery> bqOpt = gcpClientProvider.getBigQueryClient(gcpProjectId);
        if (bqOpt.isEmpty()) {
            log.error("❌ Failed to get BigQuery client for project {}.", gcpProjectId);
            return Collections.emptyList();
        }

        BigQuery bigquery = bqOpt.get();
        Optional<String> tableNameOpt = getBillingTableName(bigquery, gcpProjectId);
        if (tableNameOpt.isEmpty()) {
            log.error("❌ Failed to find billing table for project {}.", gcpProjectId);
            return Collections.emptyList();
        }

        String query = String.format(
                "SELECT " +
                        "    service.description as service, " +
                        "    location.region as region, " +
                        "    SUM(cost) as total_cost " +
                        "FROM `%s` " +
                        "WHERE project.id = '%s' AND DATE(usage_start_time) >= '%s' AND DATE(usage_start_time) <= '%s' " +
                        "GROUP BY 1, 2 " +
                        "HAVING total_cost > 0.01 " +
                        "ORDER BY total_cost DESC",
                tableNameOpt.get(), gcpProjectId,
                startDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
                endDate.format(DateTimeFormatter.ISO_LOCAL_DATE));

        try {
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
                        "Possible reasons: billing export not configured or no usage in this period.", gcpProjectId, startDate, endDate);
            }

            redisCacheService.put(cacheKey, resultList, 60 * 24);
            saveToDb(dbKey, resultList);
            return resultList;
        } catch (Exception e) {
            log.error("❌ BigQuery query failed for cost by service/region on project {}: {}", gcpProjectId,
                    e.getMessage(), e);
            if (e instanceof InterruptedException)
                Thread.currentThread().interrupt();
            return Collections.emptyList();
        }
    }
    // --- END OF DETAILED REPORTING ---

    // ===== COST BY TAG =====

    public CompletableFuture<List<GcpCostDto>> getCostByTag(String gcpProjectId, String tagKey) {
        return CompletableFuture.supplyAsync(() -> getCostByTagSync(gcpProjectId, tagKey, false), executor);
    }

    public CompletableFuture<List<GcpCostDto>> getCostByTag(String gcpProjectId, String tagKey, boolean forceRefresh) {
        return CompletableFuture.supplyAsync(() -> getCostByTagSync(gcpProjectId, tagKey, forceRefresh), executor);
    }

    public List<GcpCostDto> getCostByTagSync(String gcpProjectId, String tagKey) {
        return getCostByTagSync(gcpProjectId, tagKey, false);
    }

    public List<GcpCostDto> getCostByTagSync(String gcpProjectId, String tagKey, boolean forceRefresh) {
        String cacheKey = COST_BY_TAG_CACHE_PREFIX + gcpProjectId + ":" + tagKey;
        String dbKey    = GCP_COST_BY_TAG_DB + gcpProjectId + "::" + tagKey;

        if (!forceRefresh) {
            Optional<List<GcpCostDto>> cached = redisCacheService.get(
                    cacheKey,
                    new TypeReference<List<GcpCostDto>>() {
                    });

            if (cached.isPresent()) {
                log.info("✅ Returning cached cost by tag for project {} / tag {}", gcpProjectId, tagKey);
                return cached.get();
            }

            try {
                Optional<CachedData> dbSnap = cachedDataRepository.findById(dbKey);
                if (dbSnap.isPresent()) {
                    List<GcpCostDto> dbData = objectMapper.readValue(dbSnap.get().getJsonData(), new TypeReference<List<GcpCostDto>>() {});
                    if (dbData != null) {
                        redisCacheService.put(cacheKey, dbData, 60 * 24);
                        log.info("✅ DB hit — re-warmed Redis: {}", dbKey);
                        return dbData;
                    }
                }
            } catch (Exception e) { log.warn("DB cache miss for {}: {}", dbKey, e.getMessage()); }
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
                        "WHERE project.id = '%s' AND l.key = '%s' AND DATE(usage_start_time) >= '%s' AND DATE(usage_start_time) <= '%s' " +
                        "GROUP BY 1 ORDER BY total_cost DESC",
                tableNameOpt.get(), gcpProjectId, tagKey,
                startDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
                today.format(DateTimeFormatter.ISO_LOCAL_DATE));

        List<GcpCostDto> result = executeQuery(bigquery, query, gcpProjectId);
        redisCacheService.put(cacheKey, result, 60 * 24);
        saveToDb(dbKey, result);
        return result;
    }

    // ===== UNFILTERED MTD SPEND =====

    public CompletableFuture<Double> getUnfilteredMonthToDateSpend(String gcpProjectId) {
        return getUnfilteredMonthToDateSpend(gcpProjectId, false);
    }

    public CompletableFuture<Double> getUnfilteredMonthToDateSpend(String gcpProjectId, boolean forceRefresh) {
        return CompletableFuture.supplyAsync(() -> getUnfilteredMonthToDateSpendSync(gcpProjectId, forceRefresh), executor);
    }

    public Double getUnfilteredMonthToDateSpendSync(String gcpProjectId) {
        return getUnfilteredMonthToDateSpendSync(gcpProjectId, false);
    }

    public Double getUnfilteredMonthToDateSpendSync(String gcpProjectId, boolean forceRefresh) {
        String cacheKey = UNFILTERED_MTD_SPEND_CACHE_PREFIX + gcpProjectId;

        if (forceRefresh) {
            redisCacheService.evict(cacheKey);
            cachedDataRepository.deleteById(cacheKey);
            log.info("🔄 Force refresh: evicted MTD spend cache for project {}", gcpProjectId);
        } else {
            Optional<Double> cached = redisCacheService.get(cacheKey, Double.class);
            if (cached.isPresent()) {
                log.info("✅ Returning Redis-cached MTD spend for project {}", gcpProjectId);
                return cached.get();
            }

            // DB fallback — re-warms Redis, avoids BigQuery on cold starts
            try {
                Optional<CachedData> dbSnap = cachedDataRepository.findById(cacheKey);
                if (dbSnap.isPresent()) {
                    Double dbVal = objectMapper.readValue(dbSnap.get().getJsonData(), Double.class);
                    if (dbVal != null) {
                        log.info("✅ Returning DB-cached MTD spend for project {} — re-warming Redis", gcpProjectId);
                        redisCacheService.put(cacheKey, dbVal, 60);
                        return dbVal;
                    }
                }
            } catch (Exception e) {
                log.warn("DB snapshot load failed for MTD spend {}: {}", gcpProjectId, e.getMessage());
            }
        }

        log.info("🔍 Fetching MTD spend for project {}", gcpProjectId);

        Optional<CspSource> csp = cspSource(gcpProjectId);
        if (csp.isPresent()) {
            LocalDate now = LocalDate.now();
            double cspTotal = cspBillingQueryService.queryTotalByDateRange(
                    csp.get().client, csp.get().config, gcpProjectId,
                    now.withDayOfMonth(1).toString(), now.toString());
            log.info("🏷️ CSP-managed project {} — MTD spend {} from reseller dataset", gcpProjectId, cspTotal);
            if (cspTotal > 0.0) {
                redisCacheService.put(cacheKey, cspTotal, 60);
                saveToDb(cacheKey, cspTotal);
            } else {
                redisCacheService.put(cacheKey, cspTotal, EMPTY_SECTION_TTL_MINUTES);
            }
            return cspTotal;
        }

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
                "SELECT SUM(cost) as total_cost FROM `%s` WHERE project.id = '%s' AND DATE(usage_start_time) >= '%s' AND DATE(usage_start_time) <= '%s'",
                tableNameOpt.get(), gcpProjectId,
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
                    if (hasRows(dbResult)) {
                        log.info("✅ Returning DB-cached billing summary for project {} — re-warming Redis", gcpProjectId);
                        redisCacheService.put(cacheKey, dbResult, 60);
                        return dbResult;
                    }
                    log.info("⚠️ DB snapshot for billing summary {} is empty — refetching", gcpProjectId);
                }
            } catch (Exception e) {
                log.warn("DB snapshot load failed for billing summary {}: {}", gcpProjectId, e.getMessage());
            }
        } else {
            redisCacheService.evict(cacheKey);
        }

        log.info("🔍 Fetching billing summary for project {}", gcpProjectId);

        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.withDayOfMonth(1);

        List<GcpCostDto> result = fetchCostData(gcpProjectId, "service.description", startDate, endDate);
        cacheCostData(cacheKey, cacheKey, result, "billing summary", gcpProjectId);
        return result;
    }

    // ===== HISTORICAL COSTS =====

    public CompletableFuture<List<GcpCostDto>> getHistoricalCosts(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> getHistoricalCostsSync(gcpProjectId, false), executor);
    }

    public CompletableFuture<List<GcpCostDto>> getHistoricalCosts(String gcpProjectId, boolean forceRefresh) {
        return CompletableFuture.supplyAsync(() -> getHistoricalCostsSync(gcpProjectId, forceRefresh), executor);
    }

    public List<GcpCostDto> getHistoricalCostsSync(String gcpProjectId) {
        return getHistoricalCostsSync(gcpProjectId, false);
    }

    public List<GcpCostDto> getHistoricalCostsSync(String gcpProjectId, boolean forceRefresh) {
        String cacheKey = HISTORICAL_COSTS_CACHE_PREFIX + gcpProjectId;
        String dbKey    = GCP_COST_HISTORY_DB + gcpProjectId;

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
                Optional<CachedData> dbSnapshot = cachedDataRepository.findById(dbKey);
                if (dbSnapshot.isPresent()) {
                    List<GcpCostDto> dbResult = objectMapper.readValue(dbSnapshot.get().getJsonData(), new TypeReference<>() {});
                    log.info("✅ Returning DB-cached historical costs for project {} — re-warming Redis", gcpProjectId);
                    redisCacheService.put(cacheKey, dbResult, 60 * 24);
                    return dbResult;
                }
            } catch (Exception e) {
                log.warn("DB snapshot load failed for historical costs {}: {}", gcpProjectId, e.getMessage());
            }
        } else {
            redisCacheService.evict(cacheKey);
        }

        log.info("🔍 Fetching historical costs for project {} — composing from snapshots + BigQuery", gcpProjectId);

        // CSP-managed: the reseller export already holds every month for this project, so
        // read it directly rather than composing from snapshots that were never written.
        Optional<CspSource> cspHistory = cspSource(gcpProjectId);
        if (cspHistory.isPresent()) {
            List<GcpCostDto> cspResult = cspBillingQueryService.queryMonthlyHistory(
                    cspHistory.get().client, cspHistory.get().config, gcpProjectId);
            log.info("🏷️ CSP-managed project {} — {} months of history from reseller dataset",
                    gcpProjectId, cspResult.size());
            cacheCostData(cacheKey, dbKey, cspResult, "historical costs", gcpProjectId);
            return cspResult;
        }

        // Build 6-month list: closed months from DB snapshots, current month from BigQuery
        YearMonth currentYm = YearMonth.now();
        List<GcpCostDto> result = new ArrayList<>();

        for (int i = 5; i >= 1; i--) {
            YearMonth ym = currentYm.minusMonths(i);
            Optional<Double> snapshotTotal = gcpCostDataSnapshotService.readMonthlyTotal(gcpProjectId, ym);
            if (snapshotTotal.isPresent()) {
                result.add(new GcpCostDto(ym.toString(), snapshotTotal.get(), false));
            } else {
                // Snapshot missing — fall through to BigQuery for the full range below
                result.clear();
                break;
            }
        }

        // Current month always from BigQuery (data is still accumulating)
        Optional<BigQuery> bqOpt = gcpClientProvider.getBigQueryClient(gcpProjectId);
        if (bqOpt.isEmpty()) {
            if (!result.isEmpty()) {
                redisCacheService.put(cacheKey, result, 60 * 24);
                saveToDb(dbKey, result);
            }
            return result.isEmpty() ? Collections.emptyList() : result;
        }

        BigQuery bigquery = bqOpt.get();
        Optional<String> tableNameOpt = getBillingTableName(bigquery, gcpProjectId);

        if (result.size() == 5 && tableNameOpt.isPresent()) {
            // All 5 closed months came from snapshots — only query current month
            LocalDate today = LocalDate.now();
            String monthStart = today.withDayOfMonth(1).format(DateTimeFormatter.ISO_LOCAL_DATE);
            String monthEnd   = today.format(DateTimeFormatter.ISO_LOCAL_DATE);
            String currentQuery = String.format(
                    "SELECT SUM(cost) as total_cost FROM `%s` WHERE project.id = '%s' " +
                    "AND DATE(usage_start_time) >= '%s' AND DATE(usage_start_time) <= '%s'",
                    tableNameOpt.get(), gcpProjectId, monthStart, monthEnd);
            try {
                TableResult r = bigquery.query(QueryJobConfiguration.newBuilder(currentQuery).build());
                FieldValue fv = r.getValues().iterator().next().get("total_cost");
                double currentTotal = fv.isNull() ? 0.0 : fv.getDoubleValue();
                result.add(new GcpCostDto(currentYm.toString(), currentTotal, false));
            } catch (Exception e) {
                log.warn("BigQuery current-month query failed for {}: {}", gcpProjectId, e.getMessage());
                result.add(new GcpCostDto(currentYm.toString(), 0.0, false));
            }
            // Compute anomaly flag inline (month > prev * 1.2 && prev > 10)
            for (int i = 1; i < result.size(); i++) {
                double prev = result.get(i - 1).getAmount();
                double curr = result.get(i).getAmount();
                result.get(i).setAnomaly(curr > prev * 1.2 && prev > 10);
            }
        } else {
            // Snapshot incomplete — fall back to full BigQuery range query
            if (tableNameOpt.isEmpty()) return Collections.emptyList();
            LocalDate today = LocalDate.now();
            String endDate   = today.format(DateTimeFormatter.ISO_LOCAL_DATE);
            String startDate = today.minusMonths(6).withDayOfMonth(1).format(DateTimeFormatter.ISO_LOCAL_DATE);
            String query = String.format(
                    "WITH MonthlyCosts AS (SELECT FORMAT_DATE('%%Y-%%m', DATE(usage_start_time)) as name, SUM(cost) as total_cost FROM `%s` WHERE project.id = '%s' AND DATE(usage_start_time) >= '%s' AND DATE(usage_start_time) <= '%s' GROUP BY 1), "
                            + "MonthlyCostsWithLag AS (SELECT name, total_cost, LAG(total_cost, 1, 0) OVER (ORDER BY name) as prev_month_cost FROM MonthlyCosts) "
                            + "SELECT name, total_cost, (total_cost > prev_month_cost * 1.2 AND prev_month_cost > 10) as is_anomaly FROM MonthlyCostsWithLag ORDER BY name",
                    tableNameOpt.get(), gcpProjectId, startDate, endDate);
            result = executeQuery(bigquery, query, gcpProjectId);
        }

        clearCurrentMonthAnomalyFlag(result, currentYm);

        redisCacheService.put(cacheKey, result, 60 * 24);
        saveToDb(dbKey, result);
        return result;
    }

    /**
     * The month in progress cannot be compared against a completed one.
     *
     * <p>Both branches above flag a month when it exceeds the previous month by 20%, and both
     * include the current month, which has only run for as many days as have elapsed. That
     * comparison is wrong in both directions: on the 3rd of the month a genuine spike is hidden
     * because three days of spend is still less than last month's total, and on the 30th any
     * account growing at all trips the threshold. Either way the flag says nothing about the
     * current month, so it is cleared rather than shown.
     *
     * <p>Day-level spikes inside the current month are not lost — that is what
     * {@link #getCostAnomaliesSync} detects, per service, against a trailing daily baseline.
     */
    private void clearCurrentMonthAnomalyFlag(List<GcpCostDto> months, YearMonth currentYm) {
        if (months == null || currentYm == null) {
            return;
        }
        String current = currentYm.toString();
        months.stream()
                .filter(m -> current.equals(m.getName()))
                .forEach(m -> m.setAnomaly(false));
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
        String dbKey    = GCP_COST_BY_REGION_DB + gcpProjectId;

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
                Optional<CachedData> dbSnapshot = cachedDataRepository.findById(dbKey);
                if (dbSnapshot.isPresent()) {
                    List<GcpCostDto> dbResult = objectMapper.readValue(dbSnapshot.get().getJsonData(), new TypeReference<>() {});
                    if (hasRows(dbResult)) {
                        log.info("✅ Returning DB-cached cost by region for project {} — re-warming Redis", gcpProjectId);
                        redisCacheService.put(cacheKey, dbResult, 60 * 24);
                        return dbResult;
                    }
                    log.info("⚠️ DB snapshot for cost by region {} is empty — refetching", gcpProjectId);
                }
            } catch (Exception e) {
                log.warn("DB snapshot load failed for cost by region {}: {}", gcpProjectId, e.getMessage());
            }
        } else {
            redisCacheService.evict(cacheKey);
        }

        log.info("🔍 Fetching cost by region for project {}", gcpProjectId);

        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.withDayOfMonth(1);

        List<GcpCostDto> result = fetchCostData(gcpProjectId, "location.region", startDate, endDate);
        cacheCostData(cacheKey, dbKey, result, "cost by region", gcpProjectId);
        return result;
    }

    // ===== DAILY COSTS FOR FORECAST =====

    public CompletableFuture<List<Map<String, Object>>> getDailyCostsForForecast(String gcpProjectId,
            String serviceName, int days) {
        return CompletableFuture.supplyAsync(() -> getDailyCostsForForecastSync(gcpProjectId, serviceName, days, false),
                executor);
    }

    public CompletableFuture<List<Map<String, Object>>> getDailyCostsForForecast(String gcpProjectId,
            String serviceName, int days, boolean forceRefresh) {
        return CompletableFuture.supplyAsync(() -> getDailyCostsForForecastSync(gcpProjectId, serviceName, days, forceRefresh),
                executor);
    }

    public List<Map<String, Object>> getDailyCostsForForecastSync(String gcpProjectId, String serviceName, int days) {
        return getDailyCostsForForecastSync(gcpProjectId, serviceName, days, false);
    }

    public List<Map<String, Object>> getDailyCostsForForecastSync(String gcpProjectId, String serviceName, int days, boolean forceRefresh) {
        String cacheKey = DAILY_COSTS_FORECAST_CACHE_PREFIX + gcpProjectId + ":" + (serviceName != null ? serviceName : "ALL") + ":" + days;
        String dbKey    = GCP_COST_DAILY_FORECAST_DB + gcpProjectId + "::" + (serviceName != null ? serviceName : "ALL") + "::" + days;

        if (!forceRefresh) {
            Optional<List<Map<String, Object>>> cached = redisCacheService.get(
                    cacheKey,
                    new TypeReference<List<Map<String, Object>>>() {
                    });

            if (cached.isPresent()) {
                log.info("✅ Returning cached daily costs forecast for project {}", gcpProjectId);
                return cached.get();
            }

            try {
                Optional<CachedData> dbSnap = cachedDataRepository.findById(dbKey);
                if (dbSnap.isPresent()) {
                    List<Map<String, Object>> dbData = objectMapper.readValue(dbSnap.get().getJsonData(), new TypeReference<List<Map<String, Object>>>() {});
                    if (dbData != null) {
                        redisCacheService.put(cacheKey, dbData, 60 * 24);
                        log.info("✅ DB hit — re-warmed Redis: {}", dbKey);
                        return dbData;
                    }
                }
            } catch (Exception e) { log.warn("DB cache miss for {}: {}", dbKey, e.getMessage()); }
        }

        log.info("🔍 Fetching daily costs forecast for project {}", gcpProjectId);

        Optional<CspSource> cspDaily = cspSource(gcpProjectId);
        if (cspDaily.isPresent()) {
            LocalDate cspEnd = LocalDate.now();
            List<Map<String, Object>> cspRows = cspBillingQueryService.queryDailyCosts(
                    cspDaily.get().client, cspDaily.get().config, gcpProjectId, serviceName,
                    cspEnd.minusDays(days).toString(), cspEnd.toString());
            log.info("🏷️ CSP-managed project {} — {} daily cost rows from reseller dataset",
                    gcpProjectId, cspRows.size());
            cacheCostData(cacheKey, dbKey, cspRows, "daily costs forecast", gcpProjectId);
            return cspRows;
        }

        Optional<BigQuery> bqOpt = gcpClientProvider.getBigQueryClient(gcpProjectId);
        if (bqOpt.isEmpty())
            return Collections.emptyList();

        BigQuery bigquery = bqOpt.get();
        Optional<String> tableNameOpt = getBillingTableName(bigquery, gcpProjectId);
        if (tableNameOpt.isEmpty())
            return Collections.emptyList();

        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(days);

        // NOTE: serviceName is interpolated into SQL below rather than bound as a parameter.
        // The CSP path above uses a named parameter instead.
        String serviceFilter = (serviceName != null && !serviceName.isEmpty() && !"ALL".equalsIgnoreCase(serviceName))
                ? String.format("AND service.description = '%s'", serviceName.replace("'", "''"))
                : "";

        String query = String.format(
                "SELECT FORMAT_DATE('%%Y-%%m-%%d', DATE(usage_start_time)) as date, SUM(cost) as cost FROM `%s` WHERE project.id = '%s' AND DATE(usage_start_time) >= '%s' AND DATE(usage_start_time) < '%s' %s GROUP BY 1 ORDER BY 1",
                tableNameOpt.get(), gcpProjectId,
                startDate.toString(),
                endDate.toString(),
                serviceFilter);

        try {
            TableResult results = bigquery.query(QueryJobConfiguration.newBuilder(query).build());
            List<Map<String, Object>> result = StreamSupport.stream(results.iterateAll().spliterator(), false)
                    .map(row -> Map.<String, Object>of("date", row.get("date").getStringValue(), "cost",
                            row.get("cost").getDoubleValue()))
                    .collect(Collectors.toList());
            redisCacheService.put(cacheKey, result, 60 * 24);
            saveToDb(dbKey, result);
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
        return getCostBreakdown(gcpProjectId, groupBy, false);
    }

    public CompletableFuture<List<GcpCostDto>> getCostBreakdown(String gcpProjectId, String groupBy, boolean forceRefresh) {
        return CompletableFuture.supplyAsync(() -> getCostBreakdownSync(gcpProjectId, groupBy, forceRefresh), executor);
    }

    public CompletableFuture<List<GcpCostDto>> getCostBreakdown(String gcpProjectId, String groupBy, String startDate, String endDate, boolean forceRefresh) {
        return CompletableFuture.supplyAsync(() -> getCostBreakdownSync(gcpProjectId, groupBy, startDate, endDate, forceRefresh), executor);
    }

    public List<GcpCostDto> getCostBreakdownSync(String gcpProjectId, String groupBy) {
        return getCostBreakdownSync(gcpProjectId, groupBy, false);
    }

    public List<GcpCostDto> getCostBreakdownSync(String gcpProjectId, String groupBy, boolean forceRefresh) {
        String cacheKey = COST_BREAKDOWN_CACHE_PREFIX + gcpProjectId + ":" + groupBy;
        String dbKey    = GCP_COST_BREAKDOWN_DB + gcpProjectId + "::" + groupBy;

        if (forceRefresh) {
            redisCacheService.evict(cacheKey);
            cachedDataRepository.deleteById(dbKey);
            log.info("🔄 Force refresh: evicted cache for project {} / groupBy {}", gcpProjectId, groupBy);
        } else {
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
                Optional<CachedData> dbSnapshot = cachedDataRepository.findById(dbKey);
                if (dbSnapshot.isPresent()) {
                    List<GcpCostDto> dbResult = objectMapper.readValue(dbSnapshot.get().getJsonData(), new TypeReference<>() {});
                    if (hasRows(dbResult)) {
                        log.info("✅ Returning DB-cached cost breakdown for project {} / groupBy {} — re-warming Redis", gcpProjectId, groupBy);
                        redisCacheService.put(cacheKey, dbResult, 60 * 24);
                        return dbResult;
                    }
                    log.info("⚠️ DB snapshot for cost breakdown {} / {} is empty — refetching", gcpProjectId, groupBy);
                }
            } catch (Exception e) {
                log.warn("DB snapshot load failed for cost breakdown {}: {}", gcpProjectId, e.getMessage());
            }
        }

        log.info("🔍 Fetching cost breakdown for project {} / groupBy {}", gcpProjectId, groupBy);

        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.withDayOfMonth(1);

        List<GcpCostDto> result = fetchCostData(gcpProjectId, dimensionFor(groupBy), startDate, endDate);
        cacheCostData(cacheKey, dbKey, result, "cost breakdown / " + groupBy, gcpProjectId);
        return result;
    }

    /** Standard-export dimension expression for a groupBy value. */
    private static String dimensionFor(String groupBy) {
        if ("REGION".equalsIgnoreCase(groupBy))  return "location.region";
        if ("PROJECT".equalsIgnoreCase(groupBy)) return "project.name";
        return "service.description";
    }

    public List<GcpCostDto> getCostBreakdownSync(String gcpProjectId, String groupBy, String startDate, String endDate, boolean forceRefresh) {
        LocalDate start = startDate != null ? LocalDate.parse(startDate) : LocalDate.now().withDayOfMonth(1);
        LocalDate end   = endDate   != null ? LocalDate.parse(endDate)   : LocalDate.now();

        String cacheKey = COST_BREAKDOWN_CACHE_PREFIX + gcpProjectId + ":" + groupBy + ":" + start + ":" + end;
        String dbKey    = GCP_COST_BREAKDOWN_DATE_DB + gcpProjectId + "::" + groupBy + "::" + start + "::" + end;

        if (!forceRefresh) {
            Optional<List<GcpCostDto>> cached = redisCacheService.get(cacheKey, new TypeReference<List<GcpCostDto>>() {});
            if (cached.isPresent()) {
                log.info("✅ Redis hit for cost breakdown (date-range) project {} / groupBy {}", gcpProjectId, groupBy);
                return cached.get();
            }
            try {
                Optional<CachedData> dbSnap = cachedDataRepository.findById(dbKey);
                if (dbSnap.isPresent()) {
                    List<GcpCostDto> dbData = objectMapper.readValue(dbSnap.get().getJsonData(), new TypeReference<List<GcpCostDto>>() {});
                    if (hasRows(dbData)) {
                        redisCacheService.put(cacheKey, dbData, 60 * 24);
                        log.info("✅ DB hit — re-warmed Redis: {}", dbKey);
                        return dbData;
                    }
                    log.info("⚠️ DB snapshot {} is empty — refetching", dbKey);
                }
            } catch (Exception e) { log.warn("DB cache miss for {}: {}", dbKey, e.getMessage()); }
        }

        log.info("🔍 Fetching cost breakdown (date-range) for project {} / groupBy {} ({} to {})", gcpProjectId, groupBy, start, end);

        List<GcpCostDto> result = fetchCostData(gcpProjectId, dimensionFor(groupBy), start, end);
        cacheCostData(cacheKey, dbKey, result, "cost breakdown / " + groupBy + " (date-range)", gcpProjectId);
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
                "SELECT COALESCE(%s, 'Uncategorized') as name, SUM(cost) as total_cost, false as is_anomaly FROM `%s` WHERE project.id = '%s' AND DATE(usage_start_time) >= '%s' AND DATE(usage_start_time) <= '%s' GROUP BY 1 HAVING total_cost > 0 ORDER BY total_cost DESC",
                dimension, tableNameOpt.get(), gcpProjectId, startDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
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

    public String getProjectCurrencyCode(String gcpProjectId) {
        String cacheKey = CURRENCY_CACHE_PREFIX + gcpProjectId;
        Optional<String> cached = redisCacheService.get(cacheKey, new TypeReference<String>() {});
        if (cached.isPresent()) return cached.get();

        Optional<BigQuery> bqOpt = gcpClientProvider.getBigQueryClient(gcpProjectId);
        if (bqOpt.isEmpty()) return "USD";

        BigQuery bigquery = bqOpt.get();
        Optional<String> tableNameOpt = getBillingTableName(bigquery, gcpProjectId);
        if (tableNameOpt.isEmpty()) return "USD";

        try {
            String query = String.format(
                    "SELECT MAX(currency) as currency FROM `%s` WHERE project.id = '%s' LIMIT 1",
                    tableNameOpt.get(), gcpProjectId);
            TableResult r = bigquery.query(QueryJobConfiguration.newBuilder(query).build());
            Iterator<FieldValueList> it = r.getValues().iterator();
            if (it.hasNext()) {
                FieldValue fv = it.next().get("currency");
                if (!fv.isNull()) {
                    String code = fv.getStringValue();
                    redisCacheService.put(cacheKey, code, 60 * 24 * 7);
                    return code;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to fetch currency code for project {}: {}", gcpProjectId, e.getMessage());
        }
        return "USD";
    }

    /**
     * Evicts every cached GCP cost/billing artifact for a project across BOTH cache
     * layers (Redis + permanent DB snapshot). Call this whenever the billing source
     * changes (e.g. billing export table update) or on a user-triggered force refresh,
     * so the next query re-reads from BigQuery against the current billing table
     * instead of serving stale section-level caches.
     */
    public void evictAllCostCaches(String gcpProjectId) {
        if (gcpProjectId == null || gcpProjectId.isBlank()) {
            log.warn("Skipping GCP cost cache eviction: blank project id");
            return;
        }

        // Redis-backed caches (keyed by the gcp:* prefixes).
        String[] redisPrefixes = {
                COST_BY_TAG_CACHE_PREFIX,
                UNFILTERED_MTD_SPEND_CACHE_PREFIX,
                BILLING_SUMMARY_CACHE_PREFIX,
                HISTORICAL_COSTS_CACHE_PREFIX,
                COST_BY_REGION_CACHE_PREFIX,
                DAILY_COSTS_FORECAST_CACHE_PREFIX,
                COST_BREAKDOWN_CACHE_PREFIX,
                COST_BY_SERVICE_REGION_CACHE_PREFIX,
                CURRENCY_CACHE_PREFIX
        };
        for (String prefix : redisPrefixes) {
            redisCacheService.evictByPattern(prefix + gcpProjectId + "*");
        }

        // Permanent DB snapshots. Some sections (billing-summary, MTD spend) persist
        // under their gcp:* key, others under dedicated GCP_*:: keys — clear both.
        String[] dbPrefixes = {
                // gcp:* keys also written to the DB snapshot table
                BILLING_SUMMARY_CACHE_PREFIX,
                UNFILTERED_MTD_SPEND_CACHE_PREFIX,
                HISTORICAL_COSTS_CACHE_PREFIX,
                COST_BY_REGION_CACHE_PREFIX,
                COST_BY_TAG_CACHE_PREFIX,
                // dedicated DB snapshot keys
                GCP_FINOPS_BILLING_SVC_DB,
                GCP_FINOPS_COST_REGION_DB,
                GCP_FINOPS_COST_LABEL_DB,
                GCP_FINOPS_ANOMALIES_DB,
                GCP_COST_BREAKDOWN_DB,
                GCP_COST_BREAKDOWN_DATE_DB,
                GCP_COST_HISTORY_DB,
                GCP_COST_BY_REGION_DB,
                GCP_COST_DAILY_FORECAST_DB,
                GCP_COST_BY_TAG_DB,
                GCP_COST_SVC_REGION_DB
        };
        for (String prefix : dbPrefixes) {
            try {
                int removed = cachedDataRepository.deleteByCacheKeyPrefix(prefix + gcpProjectId);
                if (removed > 0) {
                    log.info("Evicted {} DB snapshot rows for prefix {}{}", removed, prefix, gcpProjectId);
                }
            } catch (Exception e) {
                log.warn("Failed to evict DB snapshot for prefix {}{}: {}", prefix, gcpProjectId, e.getMessage());
            }
        }

        // The billing-source status is derived from the same table this eviction exists to
        // stop trusting, so it has to go too — otherwise the page keeps explaining a problem
        // against the old source after the operator has repointed the account.
        gcpBillingStatusService.evictStatus(gcpProjectId);

        log.info("✅ Cleared all GCP cost caches (Redis + DB) for project {}", gcpProjectId);
    }

    /**
     * Delegates to {@link GcpBillingStatusService}, which owns table resolution.
     *
     * <p>This used to be a second copy of the same three-tier lookup. Two copies meant the
     * status the UI is told and the table the query actually reads could disagree, which is
     * the one thing a "why is this empty" feature must never do. Behaviour for the read path
     * is unchanged, including falling through to discovery when a stored table is malformed.
     */
    private Optional<String> getBillingTableName(BigQuery bigquery, String gcpProjectId) {
        return gcpBillingStatusService.resolveExportTable(bigquery, gcpProjectId);
    }

    // ===== DATE RANGE FILTERING METHODS =====

    /**
     * Get billing summary (cost by service) with optional date range filtering.
     */
    public CompletableFuture<List<GcpCostDto>> getBillingSummaryWithDateRange(String gcpProjectId, String startDate,
            String endDate, boolean forceRefresh) {
        return CompletableFuture.supplyAsync(() -> {
            LocalDate start = startDate != null ? LocalDate.parse(startDate) : LocalDate.now().withDayOfMonth(1);
            LocalDate end = endDate != null ? LocalDate.parse(endDate) : LocalDate.now();

            String cacheKey = BILLING_SUMMARY_CACHE_PREFIX + gcpProjectId + ":" + start + ":" + end;
            String dbKey    = GCP_FINOPS_BILLING_SVC_DB + gcpProjectId + ":" + start + ":" + end;

            if (!forceRefresh) {
                Optional<List<GcpCostDto>> cached = redisCacheService.get(cacheKey, new TypeReference<List<GcpCostDto>>() {});
                if (cached.isPresent()) {
                    log.info("Returning cached billing summary for project {} (dates: {} to {})", gcpProjectId, start, end);
                    return cached.get();
                }
                try {
                    Optional<CachedData> dbSnap = cachedDataRepository.findById(dbKey);
                    if (dbSnap.isPresent()) {
                        List<GcpCostDto> dbData = objectMapper.readValue(dbSnap.get().getJsonData(), new TypeReference<List<GcpCostDto>>() {});
                        if (hasRows(dbData)) { redisCacheService.put(cacheKey, dbData, 60); log.info("✅ DB hit — re-warmed Redis: {}", dbKey); return dbData; }
                        log.info("⚠️ DB snapshot {} is empty — refetching", dbKey);
                    }
                } catch (Exception e) { log.warn("DB cache miss for {}: {}", dbKey, e.getMessage()); }
            }

            log.info("Fetching billing summary for project {} from {} to {}", gcpProjectId, start, end);

            List<GcpCostDto> result = fetchCostData(gcpProjectId, "service.description", start, end);
            cacheCostData(cacheKey, dbKey, result, "billing summary (date-range)", gcpProjectId);
            return result;
        }, executor);
    }

    /**
     * Get cost by region with optional date range filtering.
     */
    public CompletableFuture<List<GcpCostDto>> getCostByRegionWithDateRange(String gcpProjectId, String startDate,
            String endDate, boolean forceRefresh) {
        return CompletableFuture.supplyAsync(() -> {
            LocalDate start = startDate != null ? LocalDate.parse(startDate) : LocalDate.now().withDayOfMonth(1);
            LocalDate end = endDate != null ? LocalDate.parse(endDate) : LocalDate.now();

            String cacheKey = COST_BY_REGION_CACHE_PREFIX + gcpProjectId + ":" + start + ":" + end;
            String dbKey    = GCP_FINOPS_COST_REGION_DB + gcpProjectId + ":" + start + ":" + end;

            if (!forceRefresh) {
                Optional<List<GcpCostDto>> cached = redisCacheService.get(cacheKey, new TypeReference<List<GcpCostDto>>() {});
                if (cached.isPresent()) {
                    log.info("Returning cached cost by region for project {} (dates: {} to {})", gcpProjectId, start, end);
                    return cached.get();
                }
                try {
                    Optional<CachedData> dbSnap = cachedDataRepository.findById(dbKey);
                    if (dbSnap.isPresent()) {
                        List<GcpCostDto> dbData = objectMapper.readValue(dbSnap.get().getJsonData(), new TypeReference<List<GcpCostDto>>() {});
                        if (hasRows(dbData)) { redisCacheService.put(cacheKey, dbData, 60); log.info("✅ DB hit — re-warmed Redis: {}", dbKey); return dbData; }
                        log.info("⚠️ DB snapshot {} is empty — refetching", dbKey);
                    }
                } catch (Exception e) { log.warn("DB cache miss for {}: {}", dbKey, e.getMessage()); }
            }

            log.info("Fetching cost by region for project {} from {} to {}", gcpProjectId, start, end);

            List<GcpCostDto> result = fetchCostData(gcpProjectId, "location.region", start, end);
            cacheCostData(cacheKey, dbKey, result, "cost by region (date-range)", gcpProjectId);
            return result;
        }, executor);
    }

    /**
     * Get cost by label (tag) with optional date range filtering.
     */
    public CompletableFuture<List<Map<String, Object>>> getCostByLabel(String gcpProjectId, String labelKey,
            String startDate, String endDate, boolean forceRefresh) {
        return CompletableFuture.supplyAsync(() -> {
            LocalDate start = startDate != null ? LocalDate.parse(startDate) : LocalDate.now().withDayOfMonth(1);
            LocalDate end = endDate != null ? LocalDate.parse(endDate) : LocalDate.now();

            String cacheKey = COST_BY_TAG_CACHE_PREFIX + gcpProjectId + ":" + labelKey + ":" + start + ":" + end;
            String dbKey    = GCP_FINOPS_COST_LABEL_DB + gcpProjectId + ":" + labelKey + ":" + start + ":" + end;

            if (!forceRefresh) {
                Optional<List<Map<String, Object>>> cached = redisCacheService.get(cacheKey,
                        new TypeReference<List<Map<String, Object>>>() {});
                if (cached.isPresent()) {
                    log.info("Returning cached cost by label for project {} / label {} (dates: {} to {})",
                            gcpProjectId, labelKey, start, end);
                    return cached.get();
                }
                try {
                    Optional<CachedData> dbSnap = cachedDataRepository.findById(dbKey);
                    if (dbSnap.isPresent()) {
                        List<Map<String, Object>> dbData = objectMapper.readValue(dbSnap.get().getJsonData(), new TypeReference<List<Map<String, Object>>>() {});
                        if (dbData != null) { redisCacheService.put(cacheKey, dbData, 60); log.info("✅ DB hit — re-warmed Redis: {}", dbKey); return dbData; }
                    }
                } catch (Exception e) { log.warn("DB cache miss for {}: {}", dbKey, e.getMessage()); }
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
                            "WHERE project.id = '%s' AND l.key = '%s' AND DATE(usage_start_time) >= '%s' AND DATE(usage_start_time) <= '%s' "
                            +
                            "GROUP BY 1 HAVING cost > 0.01 ORDER BY cost DESC",
                    tableNameOpt.get(), gcpProjectId, labelKey,
                    start.format(DateTimeFormatter.ISO_LOCAL_DATE),
                    end.format(DateTimeFormatter.ISO_LOCAL_DATE));

            try {
                TableResult results = bigquery.query(QueryJobConfiguration.newBuilder(query).build());
                List<Map<String, Object>> result = StreamSupport.stream(results.iterateAll().spliterator(), false)
                        .map(row -> Map.<String, Object>of(
                                "labelValue", row.get("labelValue").getStringValue(),
                                "cost", row.get("cost").getDoubleValue()))
                        .collect(Collectors.toList());
                redisCacheService.put(cacheKey, result, 60);
                saveToDb(dbKey, result);
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
            String gcpProjectId, boolean forceRefresh) {
        return CompletableFuture.supplyAsync(() -> getCostAnomaliesSync(gcpProjectId, forceRefresh), executor);
    }

    public List<com.xammer.cloud.dto.DashboardData.CostAnomaly> getCostAnomaliesSync(String gcpProjectId, boolean forceRefresh) {
        String cacheKey = COST_ANOMALIES_CACHE_PREFIX + gcpProjectId;
        String dbKey    = GCP_FINOPS_ANOMALIES_DB + gcpProjectId;

        if (!forceRefresh) {
            Optional<List<com.xammer.cloud.dto.DashboardData.CostAnomaly>> cached = redisCacheService.get(
                    cacheKey,
                    new TypeReference<List<com.xammer.cloud.dto.DashboardData.CostAnomaly>>() {});
            if (cached.isPresent()) {
                log.info("Returning cached cost anomalies for project {}", gcpProjectId);
                return cached.get();
            }
            try {
                Optional<CachedData> dbSnap = cachedDataRepository.findById(dbKey);
                if (dbSnap.isPresent()) {
                    List<com.xammer.cloud.dto.DashboardData.CostAnomaly> dbData = objectMapper.readValue(
                            dbSnap.get().getJsonData(), new TypeReference<List<com.xammer.cloud.dto.DashboardData.CostAnomaly>>() {});
                    if (dbData != null) { redisCacheService.put(cacheKey, dbData, 60); log.info("✅ DB hit — re-warmed Redis: {}", dbKey); return dbData; }
                }
            } catch (Exception e) { log.warn("DB cache miss for {}: {}", dbKey, e.getMessage()); }
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
                        "  WHERE project.id = '%s' AND DATE(usage_start_time) >= '%s' AND DATE(usage_start_time) <= '%s' " +
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
                tableNameOpt.get(), gcpProjectId,
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
                double baseline = row.get("avg_prev_7_days").isNull() ? 0
                        : row.get("avg_prev_7_days").getDoubleValue();
                double unexpectedSpend = row.get("daily_cost").getDoubleValue() - baseline;

                anomalies.add(new com.xammer.cloud.dto.DashboardData.CostAnomaly(
                        "GCP-ANOMALY-" + gcpProjectId.hashCode() + "-" + anomalyCounter++,
                        serviceName,
                        unexpectedSpend,
                        costDate,
                        costDate,
                        // The query already computes this to apply its 1.5x threshold; carrying
                        // it through is what lets the alert say "3.1x its usual" instead of
                        // quoting a bare number the reader has no way to size up.
                        baseline));
            }

            redisCacheService.put(cacheKey, anomalies, 60);
            saveToDb(dbKey, anomalies);
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