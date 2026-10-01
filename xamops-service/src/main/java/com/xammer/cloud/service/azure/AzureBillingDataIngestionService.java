package com.xammer.cloud.service.azure;

import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobItem;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.config.SchedulingEnvironmentConfig;
import com.xammer.cloud.config.multitenancy.TenantContext;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.azure.AzureDashboardData;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.RedisCacheService;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AzureBillingDataIngestionService {

    private static final Logger log = LoggerFactory.getLogger(AzureBillingDataIngestionService.class);
    private final CloudAccountRepository cloudAccountRepository;
    private final AzureClientProvider clientProvider;
    private final RedisCacheService redisCache;
    private final AzureCostQueryService costQueryService;
    private final CachedDataRepository cachedDataRepository;
    private final SchedulingEnvironmentConfig envConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Define cache keys for the data we will save
    public static final String AZURE_BILLING_SUMMARY_CACHE_PREFIX = "azure:billing-summary:";
    public static final String AZURE_COST_HISTORY_CACHE_PREFIX = "azure:cost-history:";
    public static final String AZURE_COST_BY_REGION_CACHE_PREFIX = "azure:cost-by-region:";
    public static final String AZURE_SERVICE_MONTHLY_CACHE_PREFIX = "azure:service-monthly:";

    /**
     * Daily cost per service: {@code service -> ISO date -> cost}.
     *
     * <p>
     * Both ingestion paths already read daily rows — the export is created with
     * {@code GranularityType.DAILY} and the REST query asks for {@code "granularity":"Daily"} — but
     * every consumer folded them into {@code MMM yyyy} buckets before anything was stored. Without
     * the daily series there is no way to detect a cost spike, which is why Azure had no anomaly
     * detection while AWS and GCP both did.
     */
    public static final String AZURE_DAILY_SERVICE_COST_CACHE_PREFIX = "azure:daily-service-cost:";

    // DB snapshot key prefixes (uppercase convention used across all Azure services)
    private static final String AZURE_COST_BILLING_SUMMARY_DB_PREFIX = "AZURE_COST_BILLING_SUMMARY::";
    private static final String AZURE_COST_HISTORY_DB_PREFIX       = "AZURE_COST_HISTORY::";
    private static final String AZURE_COST_BY_REGION_DB_PREFIX     = "AZURE_COST_BY_REGION::";
    private static final String AZURE_SERVICE_MONTHLY_DB_PREFIX    = "AZURE_COST_SERVICE_MONTHLY::";
    public static final String AZURE_DAILY_SERVICE_COST_DB_PREFIX  = "AZURE_COST_DAILY_SERVICE::";

    /**
     * Billing currency, and Azure's own USD conversion of the monthly totals.
     *
     * <p>
     * New keys rather than extra fields on the cached {@code CostHistory}, so a payload written by
     * an earlier build stays readable — a reader that finds no currency key reports the currency as
     * unknown, which is correct, instead of failing to deserialize the whole history.
     */
    public static final String AZURE_COST_CURRENCY_CACHE_PREFIX = "azure:cost-currency:";
    public static final String AZURE_COST_USD_CACHE_PREFIX      = "azure:cost-usd:";
    private static final String AZURE_COST_CURRENCY_DB_PREFIX   = "AZURE_COST_CURRENCY::";
    private static final String AZURE_COST_USD_DB_PREFIX        = "AZURE_COST_USD::";

    public AzureBillingDataIngestionService(CloudAccountRepository cloudAccountRepository,
            AzureClientProvider clientProvider,
            RedisCacheService redisCache,
            AzureCostQueryService costQueryService,
            CachedDataRepository cachedDataRepository,
            SchedulingEnvironmentConfig envConfig) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.clientProvider = clientProvider;
        this.redisCache = redisCache;
        this.costQueryService = costQueryService;
        this.cachedDataRepository = cachedDataRepository;
        this.envConfig = envConfig;
    }

    /**
     * Billing summary for a subscription: Redis, then the database mirror.
     *
     * <p>
     * Exists because the two pages that matter most read Redis and stopped there. Every write in
     * {@code processAndCacheData} puts the same payload in both tiers, and only
     * {@code AzureCostService} — which neither the dashboard nor the FinOps report uses — ever
     * consulted the database copy. So a cold or evicted Redis key showed zero spend on the
     * dashboard and the FinOps page while the correct figure sat in {@code cached_data}, and it
     * explained why cost appeared on some Azure pages and not others at the same moment.
     *
     * <p>
     * A hit from the database re-warms Redis, so the next reader on any page skips the query.
     */
    public Optional<List<AzureDashboardData.BillingSummary>> readBillingSummary(String subscriptionId) {
        String redisKey = AZURE_BILLING_SUMMARY_CACHE_PREFIX + subscriptionId;
        Optional<List<AzureDashboardData.BillingSummary>> fromRedis =
                redisCache.get(redisKey, new TypeReference<List<AzureDashboardData.BillingSummary>>() {});
        if (fromRedis.isPresent()) {
            return fromRedis;
        }
        Optional<List<AzureDashboardData.BillingSummary>> fromDb = readFromDb(
                AZURE_COST_BILLING_SUMMARY_DB_PREFIX + subscriptionId,
                new TypeReference<List<AzureDashboardData.BillingSummary>>() {});
        fromDb.ifPresent(value -> {
            log.info("Azure billing summary for {} served from the DB mirror — re-warming Redis", subscriptionId);
            redisCache.put(redisKey, value, 60 * 24);
        });
        return fromDb;
    }

    /** Cost history for a subscription: Redis, then the database mirror. See {@link #readBillingSummary}. */
    public Optional<AzureDashboardData.CostHistory> readCostHistory(String subscriptionId) {
        String redisKey = AZURE_COST_HISTORY_CACHE_PREFIX + subscriptionId;
        Optional<AzureDashboardData.CostHistory> fromRedis =
                redisCache.get(redisKey, AzureDashboardData.CostHistory.class);
        if (fromRedis.isPresent()) {
            return fromRedis;
        }
        Optional<AzureDashboardData.CostHistory> fromDb = readFromDb(
                AZURE_COST_HISTORY_DB_PREFIX + subscriptionId,
                new TypeReference<AzureDashboardData.CostHistory>() {});
        fromDb.ifPresent(value -> {
            log.info("Azure cost history for {} served from the DB mirror — re-warming Redis", subscriptionId);
            redisCache.put(redisKey, value, 60 * 24);
        });
        return fromDb;
    }

    /** Cost-by-region for a subscription: Redis, then the database mirror. */
    public Optional<Map<String, Double>> readCostByRegion(String subscriptionId) {
        String redisKey = AZURE_COST_BY_REGION_CACHE_PREFIX + subscriptionId;
        Optional<Map<String, Double>> fromRedis =
                redisCache.get(redisKey, new TypeReference<Map<String, Double>>() {});
        if (fromRedis.isPresent()) {
            return fromRedis;
        }
        Optional<Map<String, Double>> fromDb = readFromDb(
                AZURE_COST_BY_REGION_DB_PREFIX + subscriptionId,
                new TypeReference<Map<String, Double>>() {});
        fromDb.ifPresent(value -> redisCache.put(redisKey, value, 60 * 24));
        return fromDb;
    }

    private <T> Optional<T> readFromDb(String dbKey, TypeReference<T> type) {
        try {
            return cachedDataRepository.findById(dbKey)
                    .map(CachedData::getJsonData)
                    .filter(json -> json != null && !json.isBlank())
                    .map(json -> {
                        try {
                            return objectMapper.<T>readValue(json, type);
                        } catch (Exception e) {
                            log.warn("Could not deserialize Azure cost DB snapshot {}: {}", dbKey, e.getMessage());
                            return null;
                        }
                    });
        } catch (Exception e) {
            log.warn("Could not read Azure cost DB snapshot {}: {}", dbKey, e.getMessage());
            return Optional.empty();
        }
    }

    private void saveToDb(String dbKey, Object data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            CachedData entity = cachedDataRepository.findById(dbKey).orElse(new CachedData());
            entity.setCacheKey(dbKey);
            entity.setJsonData(json);
            entity.setLastUpdated(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
            cachedDataRepository.save(entity);
        } catch (Exception e) {
            log.warn("Failed to save Azure cost DB snapshot for key {}: {}", dbKey, e.getMessage());
        }
    }

    /**
     * Refreshes the Azure cost caches the dashboard and cost pages read.
     *
     * <p>
     * Was {@code @Scheduled(fixedRate = 21600000)}. A fixed rate is phased from JVM start, so
     * the run times drifted with every deploy and there was no way to place this job relative to
     * the 04:30 dashboard snapshot that consumes what it writes. A cron fixes the phase, and
     * 03:30 puts it an hour ahead of that snapshot so the cost keys are warm when the snapshot is
     * assembled. The force-refresh path also ingests inline before reading (see
     * {@code AzureDashboardService.getAndSetCostHistoryAndBilling}), so this ordering is belt and
     * braces rather than the only protection — but it is what keeps the non-forced seed path and
     * the cost pages from reading a cold cache for most of the day.
     *
     * <p>
     * Three correctness fixes travel with the cadence change:
     * <ul>
     * <li><b>Tenant context.</b> This ran {@code findAll()} with no tenant set. Scheduled threads
     * never pass through {@code TenantFilter}, so {@code TenantRoutingDataSource} had no lookup
     * key and fell through to the default datasource. That happens to be the right database in
     * the current single-tenant deployment, which is why it was never noticed; it would silently
     * ingest into the wrong tenant the moment a second one existed. Every other scheduled job in
     * the platform sets this.</li>
     * <li><b>Per-account isolation.</b> The loop had no try/catch, so one subscription with a
     * rotated secret or a deleted storage account threw and abandoned every remaining
     * subscription for the rest of the cycle — six hours of missing cost data for accounts that
     * were perfectly healthy, with nothing in the logs tying the two together.</li>
     * <li><b>Case-sensitive provider match.</b> {@code "Azure".equals(...)} where the rest of the
     * codebase uses {@code equalsIgnoreCase}. A row stored as "AZURE" or "azure" was skipped by
     * ingestion while still being picked up by the dashboard refresh, which produced the most
     * confusing possible symptom: a subscription that renders but reports zero spend.</li>
     * </ul>
     */
    @Scheduled(cron = "${xamops.azure.billing-ingestion-cron:0 30 3 * * *}", zone = "Asia/Kolkata")
    public void runDailyBillingIngestion() {
        if (!envConfig.isSchedulingEnabled()) {
            return;
        }

        log.info("Starting scheduled job: Ingest Azure billing data...");
        TenantContext.setCurrentTenant(envConfig.getCurrentTenantId());
        try {
            List<CloudAccount> azureAccounts = cloudAccountRepository.findAll().stream()
                    .filter(a -> "Azure".equalsIgnoreCase(a.getProvider()))
                    .collect(Collectors.toList());

            if (azureAccounts.isEmpty()) {
                log.info("No Azure accounts found. Skipping ingestion.");
                return;
            }

            int ingested = 0;
            int failed = 0;
            for (CloudAccount account : azureAccounts) {
                try {
                    ingestDataForAccount(account);
                    ingested++;
                } catch (Exception e) {
                    failed++;
                    log.error("Azure billing ingestion failed for subscription {} — continuing with the "
                            + "remaining {} account(s). Cause: {}",
                            account.getAzureSubscriptionId(), azureAccounts.size() - ingested - failed,
                            e.getMessage(), e);
                }
            }
            log.info("Azure billing ingestion complete — {} succeeded, {} failed", ingested, failed);
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Ingests cost data for a single account.
     */
    public void ingestDataForAccount(CloudAccount account) {
        // Check if billing export is configured
        String containerName = account.getAzureBillingContainer();
        String directoryName = account.getAzureBillingDirectory();

        // If export config is missing, fallback to API ingestion
        if (containerName == null || directoryName == null || containerName.isEmpty() || directoryName.isEmpty()) {
            ingestDataViaApi(account);
        } else {
            ingestDataViaBlob(account);
        }
    }

    private void ingestDataViaApi(CloudAccount account) {
        String subscriptionId = account.getAzureSubscriptionId();
        log.info("Ingesting billing data via API for account: {}", subscriptionId);

        AzureCostQueryService.AzureCostData data = costQueryService.fetchDetailedCostData(account);

        if (data.costByDate.isEmpty()) {
            log.warn("No cost data retrieved via API for {}", subscriptionId);
            return;
        }

        Map<String, Double> serviceMapForBilling = data.currentMonthCostByService.isEmpty()
                ? data.costByService : data.currentMonthCostByService;
        Map<String, Double> regionMapForBilling = data.currentMonthCostByRegion.isEmpty()
                ? data.costByRegion : data.currentMonthCostByRegion;
        processAndCacheData(subscriptionId, serviceMapForBilling, data.costByDate, regionMapForBilling,
                new HashMap<>(), data.costByServiceByDate, data.costByServiceByDay);

        // Currency and Azure's own USD conversion, cached beside the figures they describe.
        //
        // Kept in separate keys rather than widened into CostHistory so an existing cached payload
        // stays readable: a reader that finds no currency key treats the currency as unknown, which
        // is the honest answer, instead of failing to deserialize the whole history.
        cacheCurrencyAndUsd(subscriptionId, data.currency, data.costUsdByDate);
    }

    /**
     * Stores the billing currency and the USD series for a subscription.
     *
     * <p>
     * Both are written to Redis and mirrored to the database on the same terms as every other cost
     * key, so they survive a Redis restart and are readable by the same Redis-then-DB path.
     */
    private void cacheCurrencyAndUsd(String subscriptionId, String currency, Map<String, Double> costUsdByDate) {
        if (currency != null && !currency.isBlank()) {
            redisCache.put(AZURE_COST_CURRENCY_CACHE_PREFIX + subscriptionId, currency, 60 * 24);
            saveToDb(AZURE_COST_CURRENCY_DB_PREFIX + subscriptionId, currency);
            log.info("✅ Azure billing currency for {} is {}", subscriptionId, currency);
        }
        if (costUsdByDate != null && !costUsdByDate.isEmpty()) {
            redisCache.put(AZURE_COST_USD_CACHE_PREFIX + subscriptionId, costUsdByDate, 60 * 24);
            saveToDb(AZURE_COST_USD_DB_PREFIX + subscriptionId, costUsdByDate);
        }
    }

    /** Billing currency code for a subscription: Redis, then the database mirror. */
    public Optional<String> readCurrency(String subscriptionId) {
        String redisKey = AZURE_COST_CURRENCY_CACHE_PREFIX + subscriptionId;
        Optional<String> fromRedis = redisCache.get(redisKey, String.class);
        if (fromRedis.isPresent()) {
            return fromRedis;
        }
        Optional<String> fromDb = readFromDb(AZURE_COST_CURRENCY_DB_PREFIX + subscriptionId,
                new TypeReference<String>() {});
        fromDb.ifPresent(value -> redisCache.put(redisKey, value, 60 * 24));
        return fromDb;
    }

    /**
     * Daily cost per service — {@code service -> ISO date -> cost}: Redis, then the database mirror.
     *
     * <p>
     * Added because two callers now need this series and both were reading it by hand. Anomaly
     * detection reassembled the Redis-then-DB fallback inside
     * {@code AzureCostAnomalyService.loadDailySeries}, and forecast accuracy evaluation gave up on
     * Azure entirely — {@code ForecastAccuracyService.fetchActuals} carried a comment saying Azure had
     * "no reliable daily actuals source", which stopped being true the moment this series started
     * being cached. Without it every Azure prediction aged out as SKIPPED and the accuracy badge could
     * only ever report insufficient data.
     *
     * <p>
     * A hit from the database re-warms Redis, on the same terms as every other reader here.
     */
    public Optional<Map<String, Map<String, Double>>> readDailyServiceCosts(String subscriptionId) {
        String redisKey = AZURE_DAILY_SERVICE_COST_CACHE_PREFIX + subscriptionId;
        TypeReference<Map<String, Map<String, Double>>> type = new TypeReference<>() {};
        Optional<Map<String, Map<String, Double>>> fromRedis = redisCache.get(redisKey, type);
        if (fromRedis.isPresent()) {
            return fromRedis;
        }
        Optional<Map<String, Map<String, Double>>> fromDb = readFromDb(
                AZURE_DAILY_SERVICE_COST_DB_PREFIX + subscriptionId, type);
        fromDb.ifPresent(value -> redisCache.put(redisKey, value, 60 * 24));
        return fromDb;
    }

    /** Month label to USD total, from Azure's own conversion: Redis, then the database mirror. */
    public Optional<Map<String, Double>> readCostUsdByMonth(String subscriptionId) {
        String redisKey = AZURE_COST_USD_CACHE_PREFIX + subscriptionId;
        Optional<Map<String, Double>> fromRedis =
                redisCache.get(redisKey, new TypeReference<Map<String, Double>>() {});
        if (fromRedis.isPresent()) {
            return fromRedis;
        }
        Optional<Map<String, Double>> fromDb = readFromDb(AZURE_COST_USD_DB_PREFIX + subscriptionId,
                new TypeReference<Map<String, Double>>() {});
        fromDb.ifPresent(value -> redisCache.put(redisKey, value, 60 * 24));
        return fromDb;
    }

    private void ingestDataViaBlob(CloudAccount account) {
        String subscriptionId = account.getAzureSubscriptionId();
        String storageAccountName = account.getAzureBillingStorageAccount();
        String rgName = account.getAzureBillingRg();
        String containerName = account.getAzureBillingContainer();
        String directoryName = account.getAzureBillingDirectory();

        log.info("Ingesting billing data via Blob for account: {}", subscriptionId);

        if (storageAccountName == null || rgName == null || containerName == null || directoryName == null) {
            log.warn("Account {} is missing billing configuration. Skipping Blob ingestion.", subscriptionId);
            return;
        }

        try {
            String storageUrl = String.format("https://%s.blob.core.windows.net", storageAccountName);

            // Use OAuth credential (SP has Storage Blob Data Reader assigned by setup script)
            BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
                    .endpoint(storageUrl)
                    .credential(clientProvider.getCredential(account))
                    .buildClient();

            // Recursively find the latest CSV
            log.info("Searching for latest CSV in {}/{} recursively...", containerName, directoryName);
            Optional<BlobItem> latestCsv = blobServiceClient.getBlobContainerClient(containerName)
                    .listBlobs()
                    .stream()
                    .filter(blob -> blob.getName().startsWith(directoryName + "/"))
                    .filter(blob -> blob.getName().endsWith(".csv"))
                    .max(Comparator.comparing(blob -> blob.getProperties().getLastModified()));

            if (latestCsv.isEmpty()) {
                log.warn("No cost export CSVs found in {}/{}", containerName, directoryName);
                return;
            }

            // Read and parse the latest CSV
            BlobItem blobItem = latestCsv.get();
            log.info("Reading latest cost export for {}: {}", subscriptionId, blobItem.getName());

            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    blobServiceClient.getBlobContainerClient(containerName)
                            .getBlobClient(blobItem.getName())
                            .openInputStream(),
                    StandardCharsets.UTF_8));

            CSVParser csvParser = new CSVParser(reader, CSVFormat.DEFAULT
                    .withFirstRecordAsHeader()
                    .withIgnoreHeaderCase()
                    .withTrim());

            Map<String, Integer> headerMap = csvParser.getHeaderMap();

            // Check for forecast indicator columns
            boolean hasCostStatus = headerMap.containsKey("CostStatus") || headerMap.containsKey("coststatus");
            boolean hasChargeType = headerMap.containsKey("ChargeType") || headerMap.containsKey("chargetype");
            boolean hasPricingModel = headerMap.containsKey("PricingModel") || headerMap.containsKey("pricingmodel");

            String forecastColumn = null;
            if (hasCostStatus)
                forecastColumn = "CostStatus";
            else if (hasChargeType)
                forecastColumn = "ChargeType";
            else if (hasPricingModel)
                forecastColumn = "PricingModel";

            Map<String, Double> costByService = new HashMap<>();
            Map<String, Double> costByDate = new HashMap<>(); // Actual costs
            Map<String, Double> forecastByDate = new HashMap<>(); // Forecast costs
            Map<String, Double> costByRegion = new HashMap<>();
            Map<String, Double> currentMonthCostByService = new HashMap<>();
            Map<String, Double> currentMonthCostByRegion = new HashMap<>();
            Map<String, Map<String, Double>> costByServiceByDate = new HashMap<>();
            Map<String, Map<String, Double>> costByServiceByDay = new HashMap<>();

            DateTimeFormatter azureDateFormatter = DateTimeFormatter.ofPattern("MM/dd/yyyy");
            LocalDate today = LocalDate.now();
            // Canonical, locale-pinned. Compared below against labels this same loop produces, so
            // both sides move together — but pinning it means a label written today still matches
            // one read after a locale change. See AzureMonthLabel.
            String currentMonthKey = AzureMonthLabel.of(today);

            // Resolve cost column: new exports use "CostInUSD", old exports use "Cost"
            String costColumn;
            if (headerMap.containsKey("CostInUSD")) {
                costColumn = "CostInUSD";
            } else if (headerMap.containsKey("Cost")) {
                costColumn = "Cost";
            } else {
                costColumn = "costInUsd"; // legacy fallback
            }

            // Resolve date column: new exports use "Date", old exports use "UsageDate"
            String dateColumn = headerMap.containsKey("Date") ? "Date" : "UsageDate";

            // Resolve service column: new exports use "ProductName", old exports use "Meter"
            String serviceColumn;
            if (headerMap.containsKey("ProductName")) {
                serviceColumn = "ProductName";
            } else if (headerMap.containsKey("Meter")) {
                serviceColumn = "Meter";
            } else {
                serviceColumn = "MeterCategory";
            }

            log.info("CSV column mapping for {}: cost={}, date={}, service={}", subscriptionId, costColumn,
                    dateColumn, serviceColumn);

            for (CSVRecord record : csvParser) {
                try {
                    String service = record.get(serviceColumn);
                    double cost = Double.parseDouble(record.get(costColumn));
                    String dateStr = record.get(dateColumn);

                    String region = "Unassigned";
                    try {
                        region = record.get("ResourceLocation");
                        if (region == null || region.trim().isEmpty())
                            region = record.get("Location");
                        if (region == null || region.trim().isEmpty())
                            region = "Unassigned";
                    } catch (IllegalArgumentException e) {
                        region = "Unassigned";
                    }

                    LocalDate date = LocalDate.parse(dateStr, azureDateFormatter);
                    String monthYear = AzureMonthLabel.of(date);

                    boolean isForecast = false;
                    if (forecastColumn != null) {
                        try {
                            String columnValue = record.get(forecastColumn);
                            isForecast = "Forecast".equalsIgnoreCase(columnValue)
                                    || "Forecasted".equalsIgnoreCase(columnValue);
                        } catch (IllegalArgumentException e) {
                            isForecast = date.isAfter(today);
                        }
                    } else {
                        isForecast = date.isAfter(today);
                    }

                    // Add to service and region (mixed actual+forecast for now, typically forecast
                    // rows are separate)
                    // Usually we only sum actual costs for breakdown?
                    // Let's keep existing logic: sum everything
                    costByService.put(service, costByService.getOrDefault(service, 0.0) + cost);
                    costByRegion.put(region, costByRegion.getOrDefault(region, 0.0) + cost);

                    // Track per-service monthly costs for service trend chart
                    costByServiceByDate
                            .computeIfAbsent(service, k -> new HashMap<>())
                            .merge(monthYear, cost, Double::sum);

                    // Daily grain, for anomaly detection. Forecast rows are excluded on purpose: a
                    // projected day is not an observation, and letting one into the series would
                    // raise alerts about spend that has not happened.
                    if (!isForecast) {
                        costByServiceByDay
                                .computeIfAbsent(service, k -> new HashMap<>())
                                .merge(date.toString(), cost, Double::sum);
                    }

                    if (!isForecast && monthYear.equals(currentMonthKey)) {
                        currentMonthCostByService.put(service,
                            currentMonthCostByService.getOrDefault(service, 0.0) + cost);
                        currentMonthCostByRegion.put(region,
                            currentMonthCostByRegion.getOrDefault(region, 0.0) + cost);
                    }

                    if (isForecast) {
                        forecastByDate.put(monthYear, forecastByDate.getOrDefault(monthYear, 0.0) + cost);
                    } else {
                        costByDate.put(monthYear, costByDate.getOrDefault(monthYear, 0.0) + cost);
                    }

                } catch (Exception e) {
                    // skip
                }
            }

            csvParser.close();
            reader.close();

            Map<String, Double> serviceMapForBilling = currentMonthCostByService.isEmpty()
                    ? costByService : currentMonthCostByService;
            Map<String, Double> regionMapForBilling = currentMonthCostByRegion.isEmpty()
                    ? costByRegion : currentMonthCostByRegion;
            processAndCacheData(subscriptionId, serviceMapForBilling, costByDate, regionMapForBilling,
                    forecastByDate, costByServiceByDate, costByServiceByDay);

        } catch (Exception e) {
            log.error("Failed to ingest cost data via Blob for Azure account {}: {}. Falling back to Cost Management API.",
                    subscriptionId, e.getMessage());
            ingestDataViaApi(account);
        }
    }

    private void processAndCacheData(String subscriptionId,
            Map<String, Double> costByService,
            Map<String, Double> costByDate,
            Map<String, Double> costByRegion,
            Map<String, Double> forecastByDate,
            Map<String, Map<String, Double>> costByServiceByDate,
            Map<String, Map<String, Double>> costByServiceByDay) {

        // Handle forecast calculation if missing.
        //
        // The current month is located by resolving each key to a YearMonth rather than by building
        // a label with the JVM default locale and calling containsKey. Keys already in the cache
        // may have been written by a run under a different locale — "Sept 2026" against
        // "Sep 2026" — and a containsKey miss produced no forecast at all, silently. See
        // AzureMonthLabel.
        if (forecastByDate.isEmpty() && !costByDate.isEmpty()) {
            LocalDate today = LocalDate.now();
            java.time.YearMonth currentMonth = java.time.YearMonth.from(today);

            String currentMonthKey = costByDate.keySet().stream()
                    .filter(key -> AzureMonthLabel.isMonth(key, currentMonth))
                    .findFirst()
                    .orElse(null);

            if (currentMonthKey != null) {
                int daysInMonth = today.lengthOfMonth();
                // Days of usage actually represented, not days elapsed. The query's `to` bound is
                // midnight today, so today contributes nothing; dividing by getDayOfMonth() spread
                // the month's cost over one more day than produced it and under-forecast by about
                // 1/dayOfMonth — worst early in the month, where it matters most.
                int daysWithData = Math.max(1, today.getDayOfMonth() - 1);

                double costSoFar = costByDate.get(currentMonthKey);
                double dailyAverage = costSoFar / daysWithData;
                double projectedCost = dailyAverage * daysInMonth;

                // Emitted on the last day of the month as well. The old `daysSoFar < daysInMonth`
                // guard skipped it, so the forecast row disappeared for a day and the dashboard's
                // forecast KPI read zero every month-end. On the last day the projection equals
                // the actual, which is the right answer rather than an absent one.
                forecastByDate.put(AzureMonthLabel.forecastOf(currentMonth), projectedCost);
            }
        }

        // 1. Cache BillingSummary
        List<AzureDashboardData.BillingSummary> billingSummary = costByService.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(10)
                .map(entry -> new AzureDashboardData.BillingSummary(entry.getKey(), entry.getValue()))
                .collect(Collectors.toList());

        String billingCacheKey = AZURE_BILLING_SUMMARY_CACHE_PREFIX + subscriptionId;
        redisCache.put(billingCacheKey, billingSummary, 60 * 24);
        saveToDb(AZURE_COST_BILLING_SUMMARY_DB_PREFIX + subscriptionId, billingSummary);
        log.info("✅ Cached billing summary for {}: {} services", subscriptionId, billingSummary.size());

        // 2. Cache Cost by Region
        String regionCacheKey = AZURE_COST_BY_REGION_CACHE_PREFIX + subscriptionId;
        redisCache.put(regionCacheKey, costByRegion, 60 * 24);
        saveToDb(AZURE_COST_BY_REGION_DB_PREFIX + subscriptionId, costByRegion);
        log.info("✅ Cached cost by region for {}: {} regions", subscriptionId, costByRegion.size());

        // 3. Combine actual and forecast
        AzureDashboardData.CostHistory costHistory = new AzureDashboardData.CostHistory();
        Set<String> allMonths = new HashSet<>();
        allMonths.addAll(costByDate.keySet());
        allMonths.addAll(forecastByDate.keySet());

        List<String> labels = new ArrayList<>(allMonths);
        // Sorted through AzureMonthLabel for the same reason the lookups are: this used
        // LocalDate.parse("01 " + label, ofPattern("dd MMM yyyy")) with the default locale, so a
        // label spelled by another locale threw and fell back to LocalDate.now() — which silently
        // collapsed every unparseable month to today and scrambled the chart's x-axis order.
        // A month that genuinely cannot be parsed now sorts last instead of into the present.
        labels.sort(Comparator.comparing(
                (String s) -> AzureMonthLabel.parse(s).orElse(java.time.YearMonth.of(9999, 12)))
                // Actual before forecast within the same month, so "Sep 2026" precedes
                // "Sep 2026 (Forecast)" rather than depending on set iteration order.
                .thenComparing(AzureMonthLabel::isForecast));

        List<Double> costs = new ArrayList<>();
        List<Boolean> forecastFlags = new ArrayList<>();

        for (String monthYear : labels) {
            if (monthYear.contains("(Forecast)")) {
                String baseMonth = monthYear.replace(" (Forecast)", "");
                if (forecastByDate.containsKey(monthYear)) {
                    costs.add(forecastByDate.get(monthYear));
                } else if (forecastByDate.containsKey(baseMonth)) {
                    costs.add(forecastByDate.get(baseMonth));
                } else {
                    costs.add(0.0);
                }
                forecastFlags.add(true);
            } else {
                if (forecastByDate.containsKey(monthYear)) {
                    costs.add(forecastByDate.get(monthYear));
                    forecastFlags.add(true);
                } else {
                    costs.add(costByDate.getOrDefault(monthYear, 0.0));
                    forecastFlags.add(false);
                }
            }
        }

        costHistory.setLabels(labels);
        costHistory.setCosts(costs);
        costHistory.setForecast(forecastFlags);

        String historyCacheKey = AZURE_COST_HISTORY_CACHE_PREFIX + subscriptionId;
        redisCache.put(historyCacheKey, costHistory, 60 * 24);
        saveToDb(AZURE_COST_HISTORY_DB_PREFIX + subscriptionId, costHistory);
        log.info("✅ Cached cost history for {}: {} entries", subscriptionId, labels.size());

        // 4. Cache per-service monthly breakdown for service trend chart
        String serviceMonthlyKey = AZURE_SERVICE_MONTHLY_CACHE_PREFIX + subscriptionId;
        redisCache.put(serviceMonthlyKey, costByServiceByDate, 60 * 24);
        saveToDb(AZURE_SERVICE_MONTHLY_DB_PREFIX + subscriptionId, costByServiceByDate);
        log.info("✅ Cached service monthly breakdown for {}: {} services", subscriptionId, costByServiceByDate.size());

        // 5. Cache the daily per-service series that anomaly detection reads.
        cacheDailyServiceCosts(subscriptionId, costByServiceByDay);
    }

    /**
     * Stores the daily per-service cost series.
     *
     * <p>
     * Written even when empty is avoided deliberately: overwriting a good series with an empty one
     * because a single ingestion run produced no daily rows would silently disable anomaly detection
     * until the next successful run. The blob path in particular reads only the most recent export
     * file, which for a month-to-date export covers just the current month — so early in a month
     * there may be too few days to say anything, and the detector reports that rather than
     * overwriting what it already had.
     */
    private void cacheDailyServiceCosts(String subscriptionId,
            Map<String, Map<String, Double>> costByServiceByDay) {

        if (costByServiceByDay == null || costByServiceByDay.isEmpty()) {
            log.warn("No daily per-service cost rows for {} — leaving the previous daily series in "
                    + "place so anomaly detection is not blanked by one thin ingestion run",
                    subscriptionId);
            return;
        }

        int days = costByServiceByDay.values().stream()
                .flatMap(byDay -> byDay.keySet().stream())
                .collect(Collectors.toSet())
                .size();

        redisCache.put(AZURE_DAILY_SERVICE_COST_CACHE_PREFIX + subscriptionId, costByServiceByDay, 60 * 24);
        saveToDb(AZURE_DAILY_SERVICE_COST_DB_PREFIX + subscriptionId, costByServiceByDay);
        log.info("✅ Cached daily per-service costs for {}: {} services across {} days",
                subscriptionId, costByServiceByDay.size(), days);
    }
}