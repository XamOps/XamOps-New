package com.xammer.cloud.service.azure;

import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobItem;
import com.fasterxml.jackson.core.type.TypeReference; // <-- ADDED THIS IMPORT
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        String rgName = account.getAzureBillingRg(); // Get RG name
        String containerName = "costexports"; // From our setup
        String directoryName = "daily-actualcost"; // From our setup
        
        log.info("Ingesting billing data for account: {}", subscriptionId);

        // <-- FIX: Check for null RG or Storage Account name
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

            // Find the latest CSV in the directory
            Optional<BlobItem> latestCsv = blobServiceClient.getBlobContainerClient(containerName)
                    .listBlobsByHierarchy(directoryName + "/")
                    .stream()
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
                    .openInputStream(), StandardCharsets.UTF_8)); // <-- FIX: Use openInputStream()
            
            CSVParser csvParser = new CSVParser(reader, CSVFormat.DEFAULT
                    .withFirstRecordAsHeader()
                    .withIgnoreHeaderCase()
                    .withTrim());

            Map<String, Double> costByService = new HashMap<>();
            Map<String, Double> costByDate = new HashMap<>();
            DateTimeFormatter azureDateFormatter = DateTimeFormatter.ofPattern("MM/dd/yyyy");

            for (CSVRecord record : csvParser) {
                try {
                    String service = record.get("ResourceType");
                    double cost = Double.parseDouble(record.get("Cost"));
                    String dateStr = record.get("UsageDate");
                    
                    LocalDate date = LocalDate.parse(dateStr, azureDateFormatter);
                    String monthYear = date.format(DateTimeFormatter.ofPattern("MMM yyyy"));

                    costByService.put(service, costByService.getOrDefault(service, 0.0) + cost);
                    costByDate.put(monthYear, costByDate.getOrDefault(monthYear, 0.0) + cost);
                } catch (Exception e) {
                    log.warn("Skipping bad CSV record: {}", e.getMessage());
                }
            }
            
            csvParser.close();
            reader.close();
            
            // 1. Populate and Cache BillingSummary (Top 10 services)
            List<AzureDashboardData.BillingSummary> billingSummary = costByService.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(10)
                .map(entry -> new AzureDashboardData.BillingSummary(entry.getKey(), entry.getValue()))
                .collect(Collectors.toList());
            
            // Save to Redis
            String billingCacheKey = AZURE_BILLING_SUMMARY_CACHE_PREFIX + subscriptionId;
            redisCache.put(billingCacheKey, billingSummary, 60 * 24); // Cache for 24 hours
            log.info("Cached billing summary for {}", subscriptionId);

            // 2. Populate and Cache CostHistory
            AzureDashboardData.CostHistory costHistory = new AzureDashboardData.CostHistory();
            List<String> labels = new ArrayList<>(costByDate.keySet());
            labels.sort(Comparator.comparing(s -> LocalDate.parse("01 " + s, DateTimeFormatter.ofPattern("dd MMM yyyy"))));
            
            List<Double> costs = new ArrayList<>();
            for (String label : labels) {
                costs.add(costByDate.get(label));
            }

            costHistory.setLabels(labels);
            costHistory.setCosts(costs);
            costHistory.setAnomalies(Collections.nCopies(labels.size(), false));
            
            // Save to Redis
            String historyCacheKey = AZURE_COST_HISTORY_CACHE_PREFIX + subscriptionId;
            redisCache.put(historyCacheKey, costHistory, 60 * 24); // Cache for 24 hours
            log.info("Cached cost history for {}", subscriptionId);

        } catch (Exception e) {
            log.error("Failed to ingest cost data for Azure account {}: {}", subscriptionId, e.getMessage(), e);
        }
    }
}