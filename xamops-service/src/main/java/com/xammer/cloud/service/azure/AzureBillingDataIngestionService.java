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

    // Define cache keys for the data we will save
    public static final String AZURE_BILLING_SUMMARY_CACHE_PREFIX = "azure:billing-summary:";
    public static final String AZURE_COST_HISTORY_CACHE_PREFIX = "azure:cost-history:";
    public static final String AZURE_COST_BY_REGION_CACHE_PREFIX = "azure:cost-by-region:";

    public AzureBillingDataIngestionService(CloudAccountRepository cloudAccountRepository,
                                            AzureClientProvider clientProvider,
                                            RedisCacheService redisCache) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.clientProvider = clientProvider;
        this.redisCache = redisCache;
    }

    /**
     * Runs every 6 hours to check for new billing data.
     */
    @Scheduled(fixedRate = 21600000) // 6 hours
    public void runDailyBillingIngestion() {
        log.info("Starting scheduled job: Ingest Azure billing data...");
        List<CloudAccount> azureAccounts = cloudAccountRepository.findAll().stream()
                .filter(a -> "Azure".equals(a.getProvider()) && a.getAzureBillingStorageAccount() != null)
                .collect(Collectors.toList());

        if (azureAccounts.isEmpty()) {
            log.info("No Azure accounts with billing configured. Skipping ingestion.");
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
        String subscriptionId = account.getAzureSubscriptionId();
        String storageAccountName = account.getAzureBillingStorageAccount();
        String rgName = account.getAzureBillingRg();
        String containerName = "costexports";
        String directoryName = "daily-actualcost-0rf35m1";
        
        log.info("Ingesting billing data for account: {}", subscriptionId);

        if (storageAccountName == null || rgName == null) {
            log.warn("Account {} is missing billing storage account or RG name. Skipping.", subscriptionId);
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
                    .openInputStream(), StandardCharsets.UTF_8));
            
            CSVParser csvParser = new CSVParser(reader, CSVFormat.DEFAULT
                    .withFirstRecordAsHeader()
                    .withIgnoreHeaderCase()
                    .withTrim());

            // *** DEBUG: Log available CSV columns ***
            Map<String, Integer> headerMap = csvParser.getHeaderMap();
            log.info("CSV columns found: {}", headerMap.keySet());
            
            // Check for forecast indicator columns
            boolean hasCostStatus = headerMap.containsKey("CostStatus") || headerMap.containsKey("coststatus");
            boolean hasChargeType = headerMap.containsKey("ChargeType") || headerMap.containsKey("chargetype");
            boolean hasPricingModel = headerMap.containsKey("PricingModel") || headerMap.containsKey("pricingmodel");
            
            String forecastColumn = null;
            if (hasCostStatus) {
                forecastColumn = "CostStatus";
                log.info("✅ Found forecast indicator column: CostStatus");
            } else if (hasChargeType) {
                forecastColumn = "ChargeType";
                log.info("✅ Found forecast indicator column: ChargeType");
            } else if (hasPricingModel) {
                forecastColumn = "PricingModel";
                log.info("✅ Found forecast indicator column: PricingModel");
            } else {
                log.warn("⚠️ No forecast indicator column found. Will use date-based detection.");
            }

            Map<String, Double> costByService = new HashMap<>();
            Map<String, Double> costByDate = new HashMap<>(); // Actual costs
            Map<String, Double> forecastByDate = new HashMap<>(); // Forecast costs
            Map<String, Double> costByRegion = new HashMap<>();
            
            DateTimeFormatter azureDateFormatter = DateTimeFormatter.ofPattern("MM/dd/yyyy");
            LocalDate today = LocalDate.now();

            int forecastRecordCount = 0;
            int actualRecordCount = 0;

            for (CSVRecord record : csvParser) {
                try {
                    String service = record.get("ProductName");
                    double cost = Double.parseDouble(record.get("costInUsd"));
                    String dateStr = record.get("date");
                    
                    // Get region/location from CSV
                    String region = "Unassigned";
                    try {
                        region = record.get("ResourceLocation");
                        if (region == null || region.trim().isEmpty()) {
                            region = record.get("Location");
                        }
                        if (region == null || region.trim().isEmpty()) {
                            region = "Unassigned";
                        }
                    } catch (IllegalArgumentException e) {
                        region = "Unassigned";
                    }
                    
                    LocalDate date = LocalDate.parse(dateStr, azureDateFormatter);
                    String monthYear = date.format(DateTimeFormatter.ofPattern("MMM yyyy"));
                    
                    // Determine if this is a forecast record
                    boolean isForecast = false;
                    
                    if (forecastColumn != null) {
                        try {
                            String columnValue = record.get(forecastColumn);
                            isForecast = "Forecast".equalsIgnoreCase(columnValue) || 
                                       "Forecasted".equalsIgnoreCase(columnValue);
                        } catch (IllegalArgumentException e) {
                            isForecast = date.isAfter(today);
                        }
                    } else {
                        isForecast = date.isAfter(today);
                    }
                    
                    // Add to service breakdown (all costs)
                    costByService.put(service, costByService.getOrDefault(service, 0.0) + cost);
                    
                    // Add to region breakdown
                    costByRegion.put(region, costByRegion.getOrDefault(region, 0.0) + cost);
                    
                    // Separate actual vs forecast
                    if (isForecast) {
                        forecastByDate.put(monthYear, forecastByDate.getOrDefault(monthYear, 0.0) + cost);
                        forecastRecordCount++;
                    } else {
                        costByDate.put(monthYear, costByDate.getOrDefault(monthYear, 0.0) + cost);
                        actualRecordCount++;
                    }
                    
                } catch (Exception e) {
                    log.warn("Skipping bad CSV record: {}", e.getMessage());
                }
            }
            
            csvParser.close();
            reader.close();
            
            log.info("Parsed {} actual records and {} forecast records for {}", 
                     actualRecordCount, forecastRecordCount, subscriptionId);
            log.info("Aggregated into {} actual months and {} forecast months", 
                     costByDate.size(), forecastByDate.size());
            log.info("Found {} unique regions with costs", costByRegion.size());

            // *** CRITICAL FIX: ADD FORECAST CALCULATION WITHOUT REMOVING ACTUAL ***
            if (forecastByDate.isEmpty() && !costByDate.isEmpty()) {
                log.info("No forecast data found in CSV. Calculating end-of-month projection...");
                
                String currentMonth = today.format(DateTimeFormatter.ofPattern("MMM yyyy"));
                
                if (costByDate.containsKey(currentMonth)) {
                    int daysInMonth = today.lengthOfMonth();
                    int daysSoFar = today.getDayOfMonth();
                    
                    if (daysSoFar < daysInMonth) {
                        double costSoFar = costByDate.get(currentMonth);
                        double dailyAverage = costSoFar / daysSoFar;
                        double projectedCost = dailyAverage * daysInMonth;
                        
                        // *** KEY FIX: Keep actual in costByDate, add forecast with suffix ***
                        // This allows the chart to show both actual and forecast for current month
                        String forecastLabel = currentMonth + " (Forecast)";
                        forecastByDate.put(forecastLabel, projectedCost);
                        
                        log.info("✅ Calculated forecast for {}: ${} (${}/day × {} days)", 
                                 currentMonth, String.format("%.2f", projectedCost), 
                                 String.format("%.2f", dailyAverage), daysInMonth);
                    }
                }
            }
            
            // 1. Populate and Cache BillingSummary (Top 10 services)
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

            // 3. Combine actual and forecast data with proper flags
            AzureDashboardData.CostHistory costHistory = new AzureDashboardData.CostHistory();
            
            // Get all unique months (actual + forecast)
            Set<String> allMonths = new HashSet<>();
            allMonths.addAll(costByDate.keySet());
            allMonths.addAll(forecastByDate.keySet());
            
            // Sort chronologically
            List<String> labels = new ArrayList<>(allMonths);
            labels.sort(Comparator.comparing(s -> {
                try {
                    // Extract base month for sorting (before " (Forecast)" suffix)
                    String baseMonth = s.replace(" (Forecast)", "");
                    return LocalDate.parse("01 " + baseMonth, DateTimeFormatter.ofPattern("dd MMM yyyy"));
                } catch (Exception e) {
                    return LocalDate.now();
                }
            }));
            
            List<Double> costs = new ArrayList<>();
            List<Boolean> anomalies = new ArrayList<>();
            
            for (String monthYear : labels) {
                // Check if this is a forecast entry (has " (Forecast)" suffix)
                if (monthYear.contains("(Forecast)")) {
                    String baseMonth = monthYear.replace(" (Forecast)", "");
                    if (forecastByDate.containsKey(monthYear)) {
                        costs.add(forecastByDate.get(monthYear));
                    } else if (forecastByDate.containsKey(baseMonth)) {
                        costs.add(forecastByDate.get(baseMonth));
                    } else {
                        costs.add(0.0);
                    }
                    anomalies.add(true); // Mark as forecast
                } else {
                    if (forecastByDate.containsKey(monthYear)) {
                        costs.add(forecastByDate.get(monthYear));
                        anomalies.add(true); // Mark as forecast
                    } else {
                        costs.add(costByDate.getOrDefault(monthYear, 0.0));
                        anomalies.add(false); // Mark as actual
                    }
                }
            }

            costHistory.setLabels(labels);
            costHistory.setCosts(costs);
            costHistory.setAnomalies(anomalies);
            
            String historyCacheKey = AZURE_COST_HISTORY_CACHE_PREFIX + subscriptionId;
            redisCache.put(historyCacheKey, costHistory, 60 * 24);
            log.info("✅ Cached cost history for {}: {} total entries ({} actual, {} forecast)", 
                     subscriptionId, labels.size(), costByDate.size(), forecastByDate.size());

        } catch (Exception e) {
            log.error("Failed to ingest cost data for Azure account {}: {}", subscriptionId, e.getMessage(), e);
        }
    }
}
