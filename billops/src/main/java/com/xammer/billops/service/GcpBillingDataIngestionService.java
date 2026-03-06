package com.xammer.billops.service;

import com.xammer.billops.domain.CloudAccount;
import com.xammer.billops.dto.GcpBillingDashboardDto;
import com.xammer.billops.repository.CloudAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class GcpBillingDataIngestionService {

    private static final Logger log = LoggerFactory.getLogger(GcpBillingDataIngestionService.class);

    private final CloudAccountRepository cloudAccountRepository;
    private final GcpCostService gcpCostService;

    public GcpBillingDataIngestionService(CloudAccountRepository cloudAccountRepository,
            GcpCostService gcpCostService) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.gcpCostService = gcpCostService;
    }

    /**
     * Runs periodically to fetch and cache GCP billing data.
     * Changed to 60000ms (1 minute) temporarily for debugging.
     * Revert to 14400000 (4 hours) later for production.
     */
    @Scheduled(fixedRate = 60000)
    public void runDailyBillingIngestion() {
        log.info("🔄 [Scheduler] Starting GCP Billing Ingestion (Single DB Mode)...");
        try {
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

            log.info("✅ [Scheduler] GCP Billing Ingestion Completed.");
        } catch (Exception e) {
            log.error("💥 [Scheduler] Failed to ingest GCP billing data", e);
        }
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

        if (gcpProjectId == null || account.getGcpServiceAccountKey() == null) {
            log.error("❌ Account {} configuration invalid. Missing Project ID or Service Account Key.", accountIdentifier);
            return;
        }

        try {
            log.info("🚀 Fetching GCP billing data for Project: {} (Account ID: {})", gcpProjectId, accountIdentifier);

            Optional<GcpBillingDashboardDto> dashboardDto = gcpCostService
                    .getGcpBillingDashboardDtoAndCache(accountIdentifier);

            if (dashboardDto.isPresent()) {
                GcpBillingDashboardDto dto = dashboardDto.get();
                log.info("💾 Data Fetched & Cached! Project: {} | Total Cost This Month: ${} | Services: {}",
                        gcpProjectId,
                        dto.getCostThisMonth(),
                        dto.getServiceBreakdown() != null ? dto.getServiceBreakdown().size() : 0);
            } else {
                log.warn("⚠️ No billing data returned for GCP Project: {}", gcpProjectId);
            }

        } catch (IllegalArgumentException e) {
            log.error("❌ Invalid configuration for GCP Account {}: {}", accountIdentifier, e.getMessage());
        } catch (IllegalStateException e) {
            log.error("❌ Billing table not found for GCP Project {}: {}", gcpProjectId, e.getMessage());
            log.error("💡 Hint: Ensure BigQuery billing export is configured for this project.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("💥 Thread interrupted while ingesting GCP data for Project {}: {}", gcpProjectId, e.getMessage(), e);
        } catch (Exception e) {
            log.error("💥 Critical Error ingesting GCP data for Project {}: {}", gcpProjectId, e.getMessage(), e);
        }
    }
}