package com.xammer.billops.service.azure;

import com.azure.core.credential.TokenCredential;
import com.azure.core.management.profile.AzureProfile;
import com.azure.resourcemanager.costmanagement.CostManagementManager;
// --- CORRECTED IMPORTS for CostManagement 1.0.0-beta.4 ---
import com.azure.resourcemanager.costmanagement.models.Export;
import com.azure.resourcemanager.costmanagement.models.ExportDataset;  // Use ExportDataset
import com.azure.resourcemanager.costmanagement.models.ExportDatasetConfiguration;  // Use ExportDatasetConfiguration
import com.azure.resourcemanager.costmanagement.models.ExportDefinition;
import com.azure.resourcemanager.costmanagement.models.ExportDeliveryInfo;
import com.azure.resourcemanager.costmanagement.models.ExportDeliveryDestination;
import com.azure.resourcemanager.costmanagement.models.ExportRecurrencePeriod;
import com.azure.resourcemanager.costmanagement.models.ExportSchedule;
import com.azure.resourcemanager.costmanagement.models.ExportType;
import com.azure.resourcemanager.costmanagement.models.FormatType;
import com.azure.resourcemanager.costmanagement.models.GranularityType;  // May need this
import com.azure.resourcemanager.costmanagement.models.RecurrenceType;
import com.azure.resourcemanager.costmanagement.models.StatusType;
import com.azure.resourcemanager.costmanagement.models.TimeframeType;
// --- END OF IMPORTS ---
import com.xammer.billops.domain.CloudAccount;
import com.xammer.billops.dto.azure.AzureAccountRequestDto;
import com.xammer.billops.repository.CloudAccountRepository;
import com.xammer.billops.service.AzureClientProvider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;       // <-- IMPORT ADDED
import java.util.HashMap;   // <-- IMPORT ADDED
import java.util.UUID;      // <-- IMPORT ADDED

@Service
public class AzureCostManagementService {

    private static final Logger log = LoggerFactory.getLogger(AzureCostManagementService.class);
    private final AzureClientProvider clientProvider;
    private final CloudAccountRepository cloudAccountRepository;

    private static final String DAILY_EXPORT_NAME = "xamops-daily-actualcost";
    private static final String CONTAINER_NAME = "costexports"; // This can remain hardcoded as the main container

    public AzureCostManagementService(AzureClientProvider clientProvider, CloudAccountRepository cloudAccountRepository) {
        this.clientProvider = clientProvider;
        this.cloudAccountRepository = cloudAccountRepository;
    }

    /**
     * Asynchronously sets up cost exports for a newly added Azure account.
     * This version assumes the PowerShell script has already created the RG and Storage Account.
     *
     * @return A Map containing the "containerName" and "directoryName" for the created export.
     */
    @Async("gcpTaskExecutor")
    // --- SIGNATURE CHANGED from void to Map<String, String> ---
    public Map<String, String> setupCostExports(CloudAccount account, AzureAccountRequestDto request) {
        String subscriptionId = account.getAzureSubscriptionId();
        String scope = "/subscriptions/" + subscriptionId;
        
        log.info("Starting cost export setup for Azure subscription {}...", subscriptionId);

        try {
            // Get billing info from the DTO
            String billingRg = request.getBillingResourceGroup();
            String storageAccountId = request.getBillingStorageAccountId();

            if (billingRg == null || billingRg.isEmpty() || storageAccountId == null || storageAccountId.isEmpty()) {
                log.error("Failed to set up cost exports for {}: JSON payload was missing 'billing_resource_group' or 'billing_storage_account_id'.", subscriptionId);
                // --- THROW EXCEPTION INSTEAD OF RETURNING VOID ---
                throw new IllegalArgumentException("Billing Resource Group or Storage Account ID was missing.");
            }

            // Save billing info to CloudAccount entity
            account.setAzureBillingRg(billingRg);
            String storageAccountName = storageAccountId.substring(storageAccountId.lastIndexOf('/') + 1);
            account.setAzureBillingStorageAccount(storageAccountName);
            // We save the account here, but will save again in the controller with directory info
            cloudAccountRepository.save(account); 
            log.info("Saved billing RG '{}' and Storage Account '{}' to database.", billingRg, storageAccountName);

            TokenCredential credential = clientProvider.getCredential(subscriptionId);
            AzureProfile profile = new AzureProfile(account.getAzureTenantId(), account.getAzureSubscriptionId(), com.azure.core.management.AzureEnvironment.AZURE);
            
            // 1. Get Cost Management Client
            CostManagementManager costManager = CostManagementManager.authenticate(credential, profile);
            
            // 2. Create Daily Recurring Export
            log.info("Creating daily cost export rule '{}'...", DAILY_EXPORT_NAME);
            
            // --- GENERATE DYNAMIC DIRECTORY NAME ---
            String dynamicDirectoryName = "daily-actualcost-" + UUID.randomUUID().toString().substring(0, 8);
            log.info("Generated dynamic export directory for {}: {}", subscriptionId, dynamicDirectoryName);

            // Create ExportDefinition with ExportDataset (not QueryDataset)
            ExportDefinition dailyDefinition = new ExportDefinition()
                    .withType(ExportType.ACTUAL_COST)
                    .withTimeframe(TimeframeType.MONTH_TO_DATE)
                    .withDataSet(new ExportDataset()
                        .withConfiguration(new ExportDatasetConfiguration()
                            // Note: This column list differs from the one in AzureBillingDataIngestionService.
                            // You should make these lists consistent across both files.
                            .withColumns(List.of("ResourceGroup", "ResourceType", "Meter", "Cost", "UsageDate"))) 
                        .withGranularity(GranularityType.DAILY));

            // Create ExportDeliveryInfo with ExportDeliveryDestination
            ExportDeliveryInfo dailyDelivery = new ExportDeliveryInfo()
                    .withDestination(new ExportDeliveryDestination()
                            .withResourceId(storageAccountId)
                            .withContainer(CONTAINER_NAME)
                            // --- USE THE DYNAMIC DIRECTORY NAME ---
                            .withRootFolderPath(dynamicDirectoryName));

            // Create ExportSchedule with StatusType.ACTIVE
            ExportSchedule dailySchedule = new ExportSchedule()
                    .withStatus(StatusType.ACTIVE)
                    .withRecurrence(RecurrenceType.DAILY)
                    .withRecurrencePeriod(new ExportRecurrencePeriod()
                            .withFrom(OffsetDateTime.now().truncatedTo(ChronoUnit.DAYS))
                            .withTo(OffsetDateTime.now().plusYears(5)));

            // Create or update the export using fluent API
            costManager.exports().define(DAILY_EXPORT_NAME)
                    .withExistingScope(scope)
                    .withDefinition(dailyDefinition)
                    .withDeliveryInfo(dailyDelivery)
                    .withSchedule(dailySchedule)
                    .withFormat(FormatType.CSV)
                    .create();

            log.info("Successfully created daily cost export rule for {}.", subscriptionId);

            // --- PREPARE AND RETURN THE PATH CONFIGURATION ---
            Map<String, String> exportConfig = new HashMap<>();
            exportConfig.put("containerName", CONTAINER_NAME);
            exportConfig.put("directoryName", dynamicDirectoryName);
            return exportConfig;

        } catch (Exception e) {
            log.error("Failed to set up cost exports for Azure account {}: {}. " + 
                      "Account is connected, but billing data will be unavailable.", subscriptionId, e.getMessage(), e);
            // --- RE-THROW THE EXCEPTION TO BE CAUGHT BY THE CONTROLLER ---
            throw new RuntimeException("Failed to setup cost exports: " + e.getMessage(), e);
        }
    }
}