package com.xammer.cloud.service.gcp;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.DailySpotSavings;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.DailySpotSavingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * Daily scheduler that calculates and records ongoing savings for GCP MIGs with AutoSpotting enabled.
 *
 * Unlike AWS which is event-based, this scheduler actively calculates daily savings
 * for all MIGs that have AutoSpotting enabled, ensuring continuous data in the graph.
 *
 * Runs at 11:59 PM daily to record the day's savings.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class GcpAutoSpottingScheduler {

    private final CloudAccountRepository cloudAccountRepository;
    private final DailySpotSavingsRepository savingsRepository;
    private final GcpAutoSpottingService gcpAutoSpottingService;

    /**
     * Runs every day at 11:59 PM to record daily savings for all GCP accounts with AutoSpotting enabled.
     * This ensures the graph shows continuous data for as long as AutoSpotting remains enabled.
     */
    @Scheduled(cron = "0 59 23 * * *")
    public void recordDailySavings() {
        LocalDate today = LocalDate.now();
        log.info("🕐 [GCP AutoSpotting Scheduler] Starting daily savings calculation for {}", today);

        List<CloudAccount> gcpAccounts = cloudAccountRepository.findAll().stream()
                .filter(account -> "GCP".equalsIgnoreCase(account.getProvider()))
                .filter(account -> account.getGcpProjectId() != null && !account.getGcpProjectId().isBlank())
                .toList();

        log.info("📊 [GCP AutoSpotting Scheduler] Found {} GCP accounts to process", gcpAccounts.size());

        int accountsProcessed = 0;
        int migsProcessed = 0;
        double totalSavings = 0.0;

        for (CloudAccount account : gcpAccounts) {
            try {
                log.info("🔍 [GCP Scheduler] Processing account: {} ({})",
                    account.getAccountName(), account.getGcpProjectId());

                // Get all MIGs with cost data for this account
                List<GcpAutoSpottingService.GcpAutoSpottingGroupDto> migs =
                    gcpAutoSpottingService.listMigsAllRegionsWithCosts(account.getGcpProjectId());

                // Filter only enabled MIGs
                List<GcpAutoSpottingService.GcpAutoSpottingGroupDto> enabledMigs = migs.stream()
                        .filter(mig -> mig.isEnabled)
                        .toList();

                if (enabledMigs.isEmpty()) {
                    log.debug("⏭️  [GCP Scheduler] No enabled MIGs for account {}, skipping", account.getGcpProjectId());
                    continue;
                }

                // Calculate total daily savings for this account
                double accountDailySavings = enabledMigs.stream()
                        .mapToDouble(mig -> mig.actualHourlySavings * 24) // Convert hourly to daily
                        .sum();

                if (accountDailySavings <= 0.0) {
                    log.debug("💰 [GCP Scheduler] No savings for account {}, skipping", account.getGcpProjectId());
                    continue;
                }

                // Find or create daily savings record
                DailySpotSavings dailySavings = savingsRepository
                        .findByAccountIdAndDate(account.getId(), today)
                        .orElseGet(() -> new DailySpotSavings(account, today));

                // Update with calculated savings
                dailySavings.setAmountSaved(accountDailySavings);
                dailySavings.setSwapCount(enabledMigs.size());
                savingsRepository.save(dailySavings);

                accountsProcessed++;
                migsProcessed += enabledMigs.size();
                totalSavings += accountDailySavings;

                log.info("✅ [GCP Scheduler] Saved ₹{} for account {} ({} enabled MIGs)",
                        String.format("%.2f", accountDailySavings),
                        account.getAccountName(),
                        enabledMigs.size());

            } catch (Exception e) {
                log.error("❌ [GCP Scheduler] Failed to process account {}: {}",
                        account.getGcpProjectId(), e.getMessage(), e);
            }
        }

        log.info("✅ [GCP AutoSpotting Scheduler] Daily savings calculation complete!");
        log.info("📊 Summary: {} accounts, {} MIGs, total savings: ₹{}",
                accountsProcessed, migsProcessed, String.format("%.2f", totalSavings));
    }

    /**
     * Backfill historical savings for a specific account.
     * Useful for accounts that were enabled before the scheduler was implemented.
     *
     * @param accountId The GCP project ID or internal account ID
     * @param startDate Start date for backfill
     * @param endDate End date for backfill
     */
    public void backfillHistoricalSavings(String accountId, LocalDate startDate, LocalDate endDate) {
        log.info("🔄 [GCP Scheduler] Starting backfill for account {} from {} to {}",
                accountId, startDate, endDate);

        CloudAccount account = gcpAutoSpottingService.resolveAccount(accountId);

        LocalDate currentDate = startDate;
        int daysProcessed = 0;
        double totalBackfilled = 0.0;

        while (!currentDate.isAfter(endDate)) {
            final LocalDate processingDate = currentDate;
            try {
                // Get MIGs with cost data
                List<GcpAutoSpottingService.GcpAutoSpottingGroupDto> migs =
                    gcpAutoSpottingService.listMigsAllRegionsWithCosts(account.getGcpProjectId());

                // Calculate savings for enabled MIGs
                double dailySavings = migs.stream()
                        .filter(mig -> mig.isEnabled)
                        .mapToDouble(mig -> mig.actualHourlySavings * 24)
                        .sum();

                if (dailySavings > 0.0) {
                    DailySpotSavings record = savingsRepository
                            .findByAccountIdAndDate(account.getId(), processingDate)
                            .orElseGet(() -> new DailySpotSavings(account, processingDate));

                    record.setAmountSaved(dailySavings);
                    record.setSwapCount(migs.size());
                    savingsRepository.save(record);

                    daysProcessed++;
                    totalBackfilled += dailySavings;

                    log.debug("✅ Backfilled {} with ₹{}", processingDate, String.format("%.2f", dailySavings));
                }

            } catch (Exception e) {
                log.warn("⚠️  Failed to backfill {}: {}", processingDate, e.getMessage());
            }

            currentDate = currentDate.plusDays(1);
        }

        log.info("✅ [GCP Scheduler] Backfill complete! Processed {} days, total: ₹{}",
                daysProcessed, String.format("%.2f", totalBackfilled));
    }
}
