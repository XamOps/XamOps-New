package com.xammer.cloud.service.azure;

import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobItem;
import com.fasterxml.jackson.core.type.TypeReference;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.azure.AzureDashboardData;
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

    // Define cache keys for the data we will save
    public static final String AZURE_BILLING_SUMMARY_CACHE_PREFIX = "azure:billing-summary:";
    public static final String AZURE_COST_HISTORY_CACHE_PREFIX = "azure:cost-history:";
    public static final String AZURE_COST_BY_REGION_CACHE_PREFIX = "azure:cost-by-region:";

    public AzureBillingDataIngestionService(CloudAccountRepository cloudAccountRepository,
            AzureClientProvider clientProvider,
            RedisCacheService redisCache,
            AzureCostQueryService costQueryService) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.clientProvider = clientProvider;
        this.redisCache = redisCache;
        this.costQueryService = costQueryService;
    }

    /**
     * Runs every 6 hours to check for new billing data.
     */
    @Scheduled(fixedRate = 21600000) // 6 hours
    public void runDailyBillingIngestion() {
        log.info("Starting scheduled job: Ingest Azure billing data...");
        List<CloudAccount> azureAccounts = cloudAccountRepository.findAll().stream()
                .filter(a -> "Azure".equals(a.getProvider()))
                .collect(Collectors.toList());

        if (azureAccounts.isEmpty()) {
            log.info("No Azure accounts found. Skipping ingestion.");
            return;
        }

        for (CloudAccount account : azureAccounts) {
            ingestDataForAccount(account);
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

        processAndCacheData(subscriptionId, data.costByService, data.costByDate, data.costByRegion, new HashMap<>());
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
            BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
                    .endpoint(storageUrl)
                    .credential(clientProvider.getCredential(subscriptionId))
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

            DateTimeFormatter azureDateFormatter = DateTimeFormatter.ofPattern("MM/dd/yyyy");
            LocalDate today = LocalDate.now();

            for (CSVRecord record : csvParser) {
                try {
                    String service = record.get("ProductName");
                    double cost = Double.parseDouble(record.get("costInUsd"));
                    String dateStr = record.get("date");

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
                    String monthYear = date.format(DateTimeFormatter.ofPattern("MMM yyyy"));

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

            processAndCacheData(subscriptionId, costByService, costByDate, costByRegion, forecastByDate);

        } catch (Exception e) {
            log.error("Failed to ingest cost data for Azure account {}: {}", subscriptionId, e.getMessage(), e);
        }
    }

    private void processAndCacheData(String subscriptionId,
            Map<String, Double> costByService,
            Map<String, Double> costByDate,
            Map<String, Double> costByRegion,
            Map<String, Double> forecastByDate) {

        // Handle forecast calculation if missing (same logic as before)
        if (forecastByDate.isEmpty() && !costByDate.isEmpty()) {
            LocalDate today = LocalDate.now();
            String currentMonth = today.format(DateTimeFormatter.ofPattern("MMM yyyy"));

            if (costByDate.containsKey(currentMonth)) {
                int daysInMonth = today.lengthOfMonth();
                int daysSoFar = today.getDayOfMonth();

                if (daysSoFar < daysInMonth) {
                    double costSoFar = costByDate.get(currentMonth);
                    double dailyAverage = costSoFar / daysSoFar;
                    double projectedCost = dailyAverage * daysInMonth;

                    String forecastLabel = currentMonth + " (Forecast)";
                    forecastByDate.put(forecastLabel, projectedCost);
                }
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
        log.info("✅ Cached billing summary for {}: {} services", subscriptionId, billingSummary.size());

        // 2. Cache Cost by Region
        String regionCacheKey = AZURE_COST_BY_REGION_CACHE_PREFIX + subscriptionId;
        redisCache.put(regionCacheKey, costByRegion, 60 * 24);
        log.info("✅ Cached cost by region for {}: {} regions", subscriptionId, costByRegion.size());

        // 3. Combine actual and forecast
        AzureDashboardData.CostHistory costHistory = new AzureDashboardData.CostHistory();
        Set<String> allMonths = new HashSet<>();
        allMonths.addAll(costByDate.keySet());
        allMonths.addAll(forecastByDate.keySet());

        List<String> labels = new ArrayList<>(allMonths);
        labels.sort(Comparator.comparing(s -> {
            try {
                String baseMonth = s.replace(" (Forecast)", "");
                return LocalDate.parse("01 " + baseMonth, DateTimeFormatter.ofPattern("dd MMM yyyy"));
            } catch (Exception e) {
                return LocalDate.now();
            }
        }));

        List<Double> costs = new ArrayList<>();
        List<Boolean> anomalies = new ArrayList<>();

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
                anomalies.add(true);
            } else {
                if (forecastByDate.containsKey(monthYear)) {
                    costs.add(forecastByDate.get(monthYear));
                    anomalies.add(true);
                } else {
                    costs.add(costByDate.getOrDefault(monthYear, 0.0));
                    anomalies.add(false);
                }
            }
        }

        costHistory.setLabels(labels);
        costHistory.setCosts(costs);
        costHistory.setAnomalies(anomalies);

        String historyCacheKey = AZURE_COST_HISTORY_CACHE_PREFIX + subscriptionId;
        redisCache.put(historyCacheKey, costHistory, 60 * 24);
        log.info("✅ Cached cost history for {}: {} entries", subscriptionId, labels.size());
    }
}