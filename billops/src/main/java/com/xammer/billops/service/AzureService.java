package com.xammer.billops.service;

import com.azure.core.credential.TokenCredential;
import com.azure.core.management.profile.AzureProfile;
import com.azure.resourcemanager.costmanagement.CostManagementManager;
import com.azure.resourcemanager.costmanagement.models.*;
import com.xammer.billops.domain.CloudAccount;
import com.xammer.billops.repository.CloudAccountRepository;
import com.xammer.billops.dto.azure.AzureAccountRequestDto; // Ensure you have copied this DTO to billops
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AzureService {

    private static final Logger log = LoggerFactory.getLogger(AzureService.class);
    private final AzureClientProvider clientProvider;
    private final CloudAccountRepository cloudAccountRepository;

    private static final String DAILY_EXPORT_NAME = "xamops-daily-actualcost";
    private static final String CONTAINER_NAME = "costexports"; 

    public AzureService(AzureClientProvider clientProvider, CloudAccountRepository cloudAccountRepository) {
        this.clientProvider = clientProvider;
        this.cloudAccountRepository = cloudAccountRepository;
    }

    /**
     * Sets up cost exports for a newly added Azure account.
     * mirrors logic from xamops-service AzureCostManagementService.
     */
    public Map<String, String> setupCostExports(CloudAccount account, AzureAccountRequestDto request) {
        String subscriptionId = account.getAzureSubscriptionId();
        String scope = "/subscriptions/" + subscriptionId;
        
        log.info("Starting cost export setup for Azure subscription {}...", subscriptionId);

        try {
            String billingRg = request.getBillingResourceGroup();
            String storageAccountId = request.getBillingStorageAccountId();

            if (billingRg == null || billingRg.isEmpty() || storageAccountId == null || storageAccountId.isEmpty()) {
                throw new IllegalArgumentException("Billing Resource Group or Storage Account ID was missing.");
            }

            // Update account with initial billing info
            account.setAzureBillingRg(billingRg);
            String storageAccountName = storageAccountId.substring(storageAccountId.lastIndexOf('/') + 1);
            account.setAzureBillingStorageAccount(storageAccountName);
            cloudAccountRepository.save(account); 
            
            log.info("Saved billing RG '{}' and Storage Account '{}' to database.", billingRg, storageAccountName);

            TokenCredential credential = clientProvider.getCredential(subscriptionId);
            AzureProfile profile = new AzureProfile(account.getAzureTenantId(), account.getAzureSubscriptionId(), com.azure.core.management.AzureEnvironment.AZURE);
            
            CostManagementManager costManager = CostManagementManager.authenticate(credential, profile);
            
            log.info("Creating daily cost export rule '{}'...", DAILY_EXPORT_NAME);
            
            String dynamicDirectoryName = "daily-actualcost-" + UUID.randomUUID().toString().substring(0, 8);
            log.info("Generated dynamic export directory for {}: {}", subscriptionId, dynamicDirectoryName);

            ExportDefinition dailyDefinition = new ExportDefinition()
                    .withType(ExportType.ACTUAL_COST)
                    .withTimeframe(TimeframeType.MONTH_TO_DATE)
                    .withDataSet(new ExportDataset()
                        .withConfiguration(new ExportDatasetConfiguration()
                            .withColumns(List.of("ResourceGroup", "ResourceType", "Meter", "Cost", "UsageDate"))) 
                        .withGranularity(GranularityType.DAILY));

            ExportDeliveryInfo dailyDelivery = new ExportDeliveryInfo()
                    .withDestination(new ExportDeliveryDestination()
                            .withResourceId(storageAccountId)
                            .withContainer(CONTAINER_NAME)
                            .withRootFolderPath(dynamicDirectoryName));

            ExportSchedule dailySchedule = new ExportSchedule()
                    .withStatus(StatusType.ACTIVE)
                    .withRecurrence(RecurrenceType.DAILY)
                    .withRecurrencePeriod(new ExportRecurrencePeriod()
                            .withFrom(OffsetDateTime.now().truncatedTo(ChronoUnit.DAYS))
                            .withTo(OffsetDateTime.now().plusYears(5)));

            costManager.exports().define(DAILY_EXPORT_NAME)
                    .withExistingScope(scope)
                    .withDefinition(dailyDefinition)
                    .withDeliveryInfo(dailyDelivery)
                    .withSchedule(dailySchedule)
                    .withFormat(FormatType.CSV)
                    .create();

            log.info("Successfully created daily cost export rule for {}.", subscriptionId);

            Map<String, String> exportConfig = new HashMap<>();
            exportConfig.put("containerName", CONTAINER_NAME);
            exportConfig.put("directoryName", dynamicDirectoryName);
            return exportConfig;

        } catch (Exception e) {
            log.error("Failed to set up cost exports for Azure account {}: {}", subscriptionId, e.getMessage(), e);
            throw new RuntimeException("Failed to setup cost exports: " + e.getMessage(), e);
        }
    }
}