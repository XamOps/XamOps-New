package com.xammer.billops.service;

import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobItem;
import com.xammer.billops.domain.CloudAccount;
import com.xammer.billops.dto.azure.AzureBillingDashboardDto;
import com.xammer.billops.repository.CloudAccountRepository;
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
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AzureBillingDataIngestionService {

    private static final Logger log = LoggerFactory.getLogger(AzureBillingDataIngestionService.class);
    private final CloudAccountRepository cloudAccountRepository;
    private final AzureClientProvider clientProvider;
    private final RedisCacheService redisCache;

    public static final String AZURE_DASHBOARD_CACHE_PREFIX = "azure:billing-dashboard:";

    public AzureBillingDataIngestionService(CloudAccountRepository cloudAccountRepository,
            AzureClientProvider clientProvider,
            RedisCacheService redisCache) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.clientProvider = clientProvider;
        this.redisCache = redisCache;
    }

    /**
     * Runs periodically to check for new billing data.
     * Changed to 60000ms (1 minute) temporarily for debugging so you don't have to
     * wait.
     * Revert to 14400000 (4 hours) later.
     */
    @Scheduled(fixedRate = 60000)
    public void runDailyBillingIngestion() {
        log.info("🔄 Starting scheduled job: Ingest Azure billing data...");

        List<CloudAccount> allAccounts = cloudAccountRepository.findAll();
        log.info("📊 Found {} total accounts in the database.", allAccounts.size());

        List<CloudAccount> azureAccounts = allAccounts.stream()
                .filter(this::isConfiguredAzureAccount)
                .collect(Collectors.toList());

        if (azureAccounts.isEmpty()) {
            log.warn("⚠️ No configured Azure accounts found. Ingestion skipped.");
            return;
        }

        log.info("✅ Found {} Azure accounts eligible for ingestion.", azureAccounts.size());

        for (CloudAccount account : azureAccounts) {
            ingestDataForAccount(account);
        }
    }

    private boolean isConfiguredAzureAccount(CloudAccount a) {
        String provider = a.getProvider() != null ? a.getProvider().trim() : "null";
        boolean isAzure = "Azure".equalsIgnoreCase(provider);
        boolean hasStorage = a.getAzureBillingStorageAccount() != null && !a.getAzureBillingStorageAccount().isEmpty();

        // Detailed logging to debug why an account might be skipped
        if (isAzure) {
            if (!hasStorage) {
                log.warn(
                        "⚠️ Skipping Azure Account: '{}' (ID: {}). Reason: 'azureBillingStorageAccount' is NULL/Empty.",
                        a.getAccountName(), a.getId());
            } else {
                log.info("🔎 Found Candidate: '{}' (ID: {}). Storage: {}",
                        a.getAccountName(), a.getId(), a.getAzureBillingStorageAccount());
            }
        } else {
            // Log non-azure accounts at debug level to avoid clutter, but useful if
            // provider string is wrong
            log.debug("Skipping Non-Azure Account: '{}' (Provider: {})", a.getAccountName(), provider);
        }

        return isAzure && hasStorage;
    }

    public void ingestDataForAccount(CloudAccount account) {
        String subscriptionId = account.getAzureSubscriptionId();
        String storageAccountName = account.getAzureBillingStorageAccount();
        String containerName = account.getAzureBillingContainer();
        String directoryName = account.getAzureBillingDirectory();

        // Double check in case manual calls bypass the filter
        if (storageAccountName == null || containerName == null || directoryName == null) {
            log.error("❌ Account {} configuration invalid. Missing Storage/Container/Directory.", subscriptionId);
            return;
        }

        try {
            log.info("🚀 Connecting to Azure Storage: {} | Container: {} | Directory: {}",
                    storageAccountName, containerName, directoryName);

            String storageUrl = String.format("https://%s.blob.core.windows.net", storageAccountName);

            BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
                    .endpoint(storageUrl)
                    .credential(clientProvider.getCredential(subscriptionId))
                    .buildClient();

            // Find the latest CSV
            Optional<BlobItem> latestCsv = blobServiceClient.getBlobContainerClient(containerName)
                    .listBlobs()
                    .stream()
                    .filter(blob -> blob.getName().startsWith(directoryName + "/"))
                    .filter(blob -> blob.getName().endsWith(".csv"))
                    .max(Comparator.comparing(blob -> blob.getProperties().getLastModified()));

            if (latestCsv.isEmpty()) {
                log.warn("❌ No CSV files found in {}/{}. Azure Export may not have run yet.", containerName,
                        directoryName);
                return;
            }

            BlobItem blobItem = latestCsv.get();
            log.info("📄 Found Billing CSV: {} (Size: {} bytes)", blobItem.getName(),
                    blobItem.getProperties().getContentLength());

            processCsvAndCache(blobServiceClient, containerName, blobItem.getName(), subscriptionId);

        } catch (Exception e) {
            log.error("💥 Critical Error ingesting Azure data for {}: {}", subscriptionId, e.getMessage(), e);
        }
    }

    private void processCsvAndCache(BlobServiceClient client, String container, String blobName,
            String subscriptionId) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                client.getBlobContainerClient(container).getBlobClient(blobName).openInputStream(),
                StandardCharsets.UTF_8))) {

            CSVParser csvParser = new CSVParser(reader, CSVFormat.DEFAULT
                    .withFirstRecordAsHeader()
                    .withIgnoreHeaderCase()
                    .withTrim());

            Map<String, Double> costByService = new HashMap<>();
            Map<String, Double> costByDate = new HashMap<>();
            DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("MM/dd/yyyy");

            YearMonth reportPeriod = null;
            int rowCount = 0;

            for (CSVRecord record : csvParser) {
                try {
                    String dateStr = record.get("Date");
                    if (dateStr == null)
                        dateStr = record.get("UsageDate");

                    String serviceName = record.get("ServiceName");
                    if (serviceName == null)
                        serviceName = record.get("ProductName");

                    String costStr = record.get("Cost");
                    if (costStr == null)
                        costStr = record.get("CostInUsd");
                    if (costStr == null)
                        costStr = record.get("PreTaxCost");

                    if (dateStr != null && costStr != null) {
                        double cost = Double.parseDouble(costStr);
                        LocalDate date = LocalDate.parse(dateStr, dateFormatter);

                        if (reportPeriod == null)
                            reportPeriod = YearMonth.from(date);

                        costByService.merge(serviceName != null ? serviceName : "Unknown", cost, Double::sum);
                        costByDate.merge(date.toString(), cost, Double::sum);
                        rowCount++;
                    }
                } catch (Exception e) {
                    // Ignore bad rows
                }
            }

            log.info("📊 Parsed {} rows from CSV. Processing DTO...", rowCount);

            if (reportPeriod != null) {
                AzureBillingDashboardDto dashboardDto = new AzureBillingDashboardDto();

                // Services
                List<AzureBillingDashboardDto.ServiceBreakdown> serviceList = new ArrayList<>();
                costByService.forEach(
                        (name, amount) -> serviceList.add(new AzureBillingDashboardDto.ServiceBreakdown(name, amount)));
                serviceList.sort((a, b) -> Double.compare(b.getAmount(), a.getAmount()));
                dashboardDto.setServiceBreakdown(serviceList);

                // History
                List<AzureBillingDashboardDto.CostHistory> historyList = new ArrayList<>();
                costByDate.forEach(
                        (date, amount) -> historyList.add(new AzureBillingDashboardDto.CostHistory(date, amount)));
                historyList.sort(Comparator.comparing(AzureBillingDashboardDto.CostHistory::getDate));
                dashboardDto.setCostHistory(historyList);

                // Total
                double total = serviceList.stream().mapToDouble(AzureBillingDashboardDto.ServiceBreakdown::getAmount)
                        .sum();
                dashboardDto.setTotalCost(total);

                // Save to Redis
                String specificKey = AZURE_DASHBOARD_CACHE_PREFIX + subscriptionId + ":" + reportPeriod.getYear() + ":"
                        + reportPeriod.getMonthValue();
                redisCache.put(specificKey, dashboardDto, 60 * 24);

                // If it's current month, also save as 'latest' for fallback
                if (reportPeriod.equals(YearMonth.now())) {
                    redisCache.put(AZURE_DASHBOARD_CACHE_PREFIX + subscriptionId + ":latest", dashboardDto, 60 * 24);
                }

                log.info("💾 Data Saved to Redis! Key: {} | Total Cost: ${}", specificKey, total);
            } else {
                log.warn("⚠️ CSV parsed but no valid date/cost rows found.");
            }

        } catch (Exception e) {
            log.error("❌ Error parsing CSV for {}: {}", subscriptionId, e.getMessage(), e);
        }
    }
}