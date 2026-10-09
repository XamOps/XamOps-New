package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.DailySpotSavings;
import com.xammer.cloud.dto.autospotting.CostResponse;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.DailySpotSavingsRepository;
import com.xammer.cloud.service.autospotting.AutoSpottingFeatureFlags;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * Daily scheduler that calculates and records ongoing savings for AWS ASGs with AutoSpotting enabled.
 *
 * Updated to actively calculate daily savings for all ASGs that have AutoSpotting enabled,
 * ensuring continuous data in the graph (not just event-based snapshots).
 *
 * Runs at 11:59 PM daily to record the day's savings.
 *
 * Gated by {@code autospotting.aws.savings-ledger.enabled} (default false): while it is off,
 * no AWS row is written to {@code daily_spot_savings}, because the AWS savings figures are
 * not yet reliable (AWS-13).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AutoSpottingScheduler {

    private final CloudAccountRepository cloudAccountRepository;
    private final DailySpotSavingsRepository savingsRepository;
    private final AutoSpottingService autoSpottingService;
    private final AutoSpottingFeatureFlags featureFlags;

    /**
     * Runs every day at 11:59 PM to record daily savings for all AWS accounts with AutoSpotting enabled.
     * This ensures the graph shows continuous data for as long as AutoSpotting remains enabled.
     */
    @Scheduled(cron = "0 59 23 * * *")
    public void recordDailySavings() {
        LocalDate today = LocalDate.now();
        if (!featureFlags.isAwsSavingsLedgerEnabled()) {
            log.info("[AWS AutoSpotting Scheduler] Savings ledger disabled "
                    + "(autospotting.aws.savings-ledger.enabled=false); skipping daily_spot_savings for {}", today);
            return;
        }
        log.info("🕐 [AWS AutoSpotting Scheduler] Starting daily savings calculation for {}", today);

        List<CloudAccount> awsAccounts = cloudAccountRepository.findAll().stream()
                .filter(account -> "AWS".equalsIgnoreCase(account.getProvider()))
                .filter(account -> account.getAwsAccountId() != null && !account.getAwsAccountId().isBlank())
                .toList();

        log.info("📊 [AWS AutoSpotting Scheduler] Found {} AWS accounts to process", awsAccounts.size());

        int accountsProcessed = 0;
        int asgsProcessed = 0;
        double totalSavings = 0.0;

        for (CloudAccount account : awsAccounts) {
            try {
                log.info("🔍 [AWS Scheduler] Processing account: {} ({})",
                    account.getAccountName(), account.getAwsAccountId());

                // Get cost data which includes all ASGs with their current savings
                CostResponse costData = autoSpottingService.getCostData(account);

                if (costData == null || costData.getSummary() == null) {
                    log.debug("⏭️  [AWS Scheduler] No cost data for account {}, skipping", account.getAwsAccountId());
                    continue;
                }

                // Get actual hourly savings from currently enabled ASGs
                double hourlyActualSavings = costData.getSummary().getTotalActualSavings();
                // Convert hourly to daily savings (24 hours)
                double dailySavings = hourlyActualSavings * 24;

                // Count enabled ASGs
                int enabledASGs = (int) costData.getAsgs().stream()
                        .filter(asg -> asg.getAutospottingEnabled() != null && asg.getAutospottingEnabled())
                        .count();

                // Always write the record (even if savings are 0) so the chart has
                // continuous data and doesn't fall back to event-based tiny amounts.
                DailySpotSavings dailySavingsRecord = savingsRepository
                        .findByAccountIdAndDate(account.getId(), today)
                        .orElseGet(() -> new DailySpotSavings(account, today));

                dailySavingsRecord.setAmountSaved(dailySavings);
                dailySavingsRecord.setSwapCount(enabledASGs);
                savingsRepository.save(dailySavingsRecord);

                accountsProcessed++;
                asgsProcessed += enabledASGs;
                totalSavings += dailySavings;

                log.info("✅ [AWS Scheduler] Saved ${} for account {} ({} enabled ASGs)",
                        String.format("%.2f", dailySavings),
                        account.getAccountName(),
                        enabledASGs);

            } catch (Exception e) {
                log.error("❌ [AWS Scheduler] Failed to process account {}: {}",
                        account.getAwsAccountId(), e.getMessage(), e);
            }
        }

        log.info("✅ [AWS AutoSpotting Scheduler] Daily savings calculation complete!");
        log.info("📊 Summary: {} accounts, {} ASGs, total savings: ${}",
                accountsProcessed, asgsProcessed, String.format("%.2f", totalSavings));
    }

    /**
     * Backfill historical savings for a specific account.
     * Useful for accounts that were enabled before the scheduler was updated.
     *
     * @param accountId The AWS account ID (internal database ID)
     * @param startDate Start date for backfill
     * @param endDate End date for backfill
     */
    public void backfillHistoricalSavings(Long accountId, LocalDate startDate, LocalDate endDate) {
        if (!featureFlags.isAwsSavingsLedgerEnabled()) {
            log.info("[AWS AutoSpotting Scheduler] Savings ledger disabled "
                    + "(autospotting.aws.savings-ledger.enabled=false); skipping backfill for account {}", accountId);
            return;
        }
        log.info("🔄 [AWS Scheduler] Starting backfill for account {} from {} to {}",
                accountId, startDate, endDate);

        CloudAccount account = cloudAccountRepository.findById(accountId)
                .orElseThrow(() -> new RuntimeException("Account not found: " + accountId));

        LocalDate currentDate = startDate;
        int daysProcessed = 0;
        double totalBackfilled = 0.0;

        while (!currentDate.isAfter(endDate)) {
            final LocalDate processingDate = currentDate;
            try {
                // Get cost data
                CostResponse costData = autoSpottingService.getCostData(account);

                if (costData != null && costData.getSummary() != null) {
                    double hourlyActualSavings = costData.getSummary().getTotalActualSavings();
                    double dailySavings = hourlyActualSavings * 24;

                    if (dailySavings > 0.0) {
                        DailySpotSavings record = savingsRepository
                                .findByAccountIdAndDate(account.getId(), processingDate)
                                .orElseGet(() -> new DailySpotSavings(account, processingDate));

                        record.setAmountSaved(dailySavings);
                        int enabledASGs = (int) costData.getAsgs().stream()
                                .filter(asg -> asg.getAutospottingEnabled() != null && asg.getAutospottingEnabled())
                                .count();
                        record.setSwapCount(enabledASGs);
                        savingsRepository.save(record);

                        daysProcessed++;
                        totalBackfilled += dailySavings;

                        log.debug("✅ Backfilled {} with ${}", processingDate, String.format("%.2f", dailySavings));
                    }
                }

            } catch (Exception e) {
                log.warn("⚠️  Failed to backfill {}: {}", processingDate, e.getMessage());
            }

            currentDate = currentDate.plusDays(1);
        }

        log.info("✅ [AWS Scheduler] Backfill complete! Processed {} days, total: ${}",
                daysProcessed, String.format("%.2f", totalBackfilled));
    }
}
