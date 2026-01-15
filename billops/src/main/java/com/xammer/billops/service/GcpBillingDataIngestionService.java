package com.xammer.billops.service;

import com.xammer.billops.config.multitenancy.TenantContext;
import com.xammer.billops.domain.CloudAccount;
import com.xammer.billops.dto.GcpBillingDashboardDto;
import com.xammer.billops.repository.CloudAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;

@Service
public class GcpBillingDataIngestionService {

    private static final Logger log = LoggerFactory.getLogger(GcpBillingDataIngestionService.class);

    private final CloudAccountRepository cloudAccountRepository;
    private final GcpCostService gcpCostService;
    private final DataSource masterDataSource; // Required to fetch the list of tenants

    public GcpBillingDataIngestionService(CloudAccountRepository cloudAccountRepository,
            GcpCostService gcpCostService,
            @Qualifier("masterDataSource") DataSource masterDataSource) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.gcpCostService = gcpCostService;
        this.masterDataSource = masterDataSource;
    }

    /**
     * Runs periodically to fetch and cache GCP billing data.
     * Changed to 60000ms (1 minute) temporarily for debugging.
     * Revert to 14400000 (4 hours) later for production.
     */
    @Scheduled(fixedRate = 60000)
    public void runDailyBillingIngestion() {
        log.info("🔄 [Scheduler] Starting Global Scheduled Job: Ingest GCP billing data...");

        // Check if we're in prod profile
        String[] activeProfiles = new org.springframework.core.env.StandardEnvironment().getActiveProfiles();
        boolean isProd = java.util.Arrays.asList(activeProfiles).contains("prod");

        if (!isProd) {
            // In local/UAT, process current database only (no multi-tenancy)
            log.info("🔓 LOCAL/UAT MODE: Processing current database only");
            try {
                // Process accounts in current database without tenant context switching
                List<CloudAccount> allAccounts = cloudAccountRepository.findAll();
                log.info("📊 Found {} total accounts in database.", allAccounts.size());

                List<CloudAccount> gcpAccounts = allAccounts.stream()
                        .filter(this::isConfiguredGcpAccount)
                        .toList();

                if (gcpAccounts.isEmpty()) {
                    log.warn("⚠️ No configured GCP accounts found. Skipping.");
                    return;
                }

                log.info("✅ Found {} GCP accounts eligible for ingestion.", gcpAccounts.size());

                for (CloudAccount account : gcpAccounts) {
                    ingestDataForAccount(account);
                }

                log.info("✅ [Scheduler] Local GCP Billing Ingestion Completed.");
            } catch (Exception e) {
                log.error("💥 [Scheduler] Failed to ingest GCP billing data", e);
            }
            return;
        }

        // PROD MODE: Multi-tenant processing
        log.info("🔒 PROD MODE: Multi-tenant processing");

        // 1. Fetch all active tenant IDs from the Master DB
        List<String> activeTenants;
        try {
            activeTenants = getAllActiveTenantIds();
            log.info("🏢 [Scheduler] Found {} active tenants to process.", activeTenants.size());
        } catch (Exception e) {
            log.error("💥 [Scheduler] Failed to fetch active tenants from Master DB. Aborting job.", e);
            return;
        }

        // 2. Iterate through each tenant and run ingestion in their context
        for (String tenantId : activeTenants) {
            processTenant(tenantId);
        }

        log.info("✅ [Scheduler] Global GCP Billing Ingestion Completed.");
    }

    /**
     * Handles the logic of switching context and processing accounts for a single
     * tenant.
     */
    private void processTenant(String tenantId) {
        // A. Set the Context: All subsequent Repository calls will now go to this
        // Tenant's DB
        TenantContext.setCurrentTenant(tenantId);
        log.info("👉 [Scheduler] Switched to Tenant Context: '{}'", tenantId);

        try {
            List<CloudAccount> allAccounts = cloudAccountRepository.findAll();
            log.info("📊 [Tenant: {}] Found {} total accounts in database.", tenantId, allAccounts.size());

            List<CloudAccount> gcpAccounts = allAccounts.stream()
                    .filter(this::isConfiguredGcpAccount)
                    .toList();

            if (gcpAccounts.isEmpty()) {
                log.warn("⚠️ [Tenant: {}] No configured GCP accounts found. Skipping.", tenantId);
                return;
            }

            log.info("✅ [Tenant: {}] Found {} GCP accounts eligible for ingestion.", tenantId, gcpAccounts.size());

            for (CloudAccount account : gcpAccounts) {
                ingestDataForAccount(account);
            }

        } catch (Exception e) {
            log.error("❌ [Tenant: {}] Error processing tenant: {}", tenantId, e.getMessage(), e);
        } finally {
            // B. Clean up: Always clear context to prevent data leakage to other
            // threads/jobs
            TenantContext.clear();
            log.debug("🧹 [Scheduler] Cleared Tenant Context for '{}'", tenantId);
        }
    }

    /**
     * Helper to query the Master Database for all active tenants.
     */
    private List<String> getAllActiveTenantIds() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(masterDataSource);
        // Assumes your master DB has a table 'tenant_config' with 'tenant_id' and
        // 'active' columns
        return jdbcTemplate.queryForList("SELECT tenant_id FROM tenant_config WHERE active = true", String.class);
    }

    private boolean isConfiguredGcpAccount(CloudAccount a) {
        String provider = a.getProvider() != null ? a.getProvider().trim() : "null";
        boolean isGcp = "GCP".equalsIgnoreCase(provider);
        boolean hasProjectId = a.getGcpProjectId() != null && !a.getGcpProjectId().isEmpty();
        String serviceAccountKey = a.getGcpServiceAccountKey();
        boolean hasServiceAccountKey = serviceAccountKey != null && !serviceAccountKey.isEmpty();

        // Detailed logging to debug why an account might be skipped
        if (isGcp) {
            if (!hasProjectId) {
                log.warn(
                        "⚠️ [Account Check] Skipping GCP Account: '{}' (ID: {}). Reason: 'gcpProjectId' is NULL/Empty.",
                        a.getAccountName(), a.getId());
            } else if (!hasServiceAccountKey) {
                log.warn(
                        "⚠️ [Account Check] Skipping GCP Account: '{}' (ID: {}). Reason: 'gcpServiceAccountKey' is NULL/Empty.",
                        a.getAccountName(), a.getId());
            } else {
                log.info("🔎 [Account Check] Found Candidate: '{}' (ID: {}). Project ID: {}",
                        a.getAccountName(), a.getId(), a.getGcpProjectId());
            }
        } else {
            // Log non-GCP accounts at debug level to avoid cluttering the logs
            log.debug("👻 [Account Check] Skipping Non-GCP Account: '{}' (Provider: {})", a.getAccountName(), provider);
        }

        return isGcp && hasProjectId && hasServiceAccountKey;
    }

    public void ingestDataForAccount(CloudAccount account) {
        String gcpProjectId = account.getGcpProjectId();
        String accountIdentifier = account.getId().toString();
        String currentTenant = TenantContext.getCurrentTenant(); // For logging context

        // Double check in case manual calls bypass the filter
        if (gcpProjectId == null || account.getGcpServiceAccountKey() == null) {
            log.error("❌ [Tenant: {}] Account {} configuration invalid. Missing Project ID or Service Account Key.",
                    currentTenant, accountIdentifier);
            return;
        }

        try {
            log.info("🚀 [Tenant: {}] Fetching GCP billing data for Project: {} (Account ID: {})",
                    currentTenant, gcpProjectId, accountIdentifier);

            // Call the GcpCostService to fetch and cache the data
            // Since TenantContext is set, GcpCostService will look up this account in the
            // correct DB
            Optional<GcpBillingDashboardDto> dashboardDto = gcpCostService
                    .getGcpBillingDashboardDtoAndCache(accountIdentifier);

            if (dashboardDto.isPresent()) {
                GcpBillingDashboardDto dto = dashboardDto.get();
                log.info(
                        "💾 [Tenant: {}] Data Fetched & Cached! Project: {} | Total Cost This Month: ${} | Services: {}",
                        currentTenant,
                        gcpProjectId,
                        dto.getCostThisMonth(),
                        dto.getServiceBreakdown() != null ? dto.getServiceBreakdown().size() : 0);
            } else {
                log.warn("⚠️ [Tenant: {}] No billing data returned for GCP Project: {}", currentTenant, gcpProjectId);
            }

        } catch (IllegalArgumentException e) {
            log.error("❌ [Tenant: {}] Invalid configuration for GCP Account {}: {}", currentTenant, accountIdentifier,
                    e.getMessage());
        } catch (IllegalStateException e) {
            log.error("❌ [Tenant: {}] Billing table not found for GCP Project {}: {}", currentTenant, gcpProjectId,
                    e.getMessage());
            log.error("💡 Hint: Ensure BigQuery billing export is configured for this project.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("💥 [Tenant: {}] Thread interrupted while ingesting GCP data for Project {}: {}", currentTenant,
                    gcpProjectId, e.getMessage(), e);
        } catch (Exception e) {
            log.error("💥 [Tenant: {}] Critical Error ingesting GCP data for Project {}: {}", currentTenant,
                    gcpProjectId, e.getMessage(), e);
        }
    }
}