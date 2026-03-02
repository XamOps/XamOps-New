package com.xammer.cloud.service.azure;

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
 * Daily scheduler that calculates and records ongoing savings for Azure VMSS with AutoSpotting enabled.
 *
 * Runs at 11:59 PM daily to record the day's savings for all Azure accounts.
 * Follows the same pattern as GcpAutoSpottingScheduler.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AzureAutoSpottingScheduler {

    private final CloudAccountRepository cloudAccountRepository;
    private final DailySpotSavingsRepository savingsRepository;
    private final AzureAutoSpottingService azureAutoSpottingService;

    @Scheduled(cron = "0 59 23 * * *")
    public void recordDailySavings() {
        LocalDate today = LocalDate.now();
        log.info("🕐 [Azure AutoSpotting Scheduler] Starting daily savings calculation for {}", today);

        List<CloudAccount> azureAccounts = cloudAccountRepository.findAll().stream()
                .filter(account -> "AZURE".equalsIgnoreCase(account.getProvider()))
                .filter(account -> account.getAzureSubscriptionId() != null
                        && !account.getAzureSubscriptionId().isBlank())
                .toList();

        log.info("📊 [Azure AutoSpotting Scheduler] Found {} Azure accounts to process", azureAccounts.size());

        int accountsProcessed = 0;
        int vmssProcessed = 0;
        double totalSavings = 0.0;

        for (CloudAccount account : azureAccounts) {
            try {
                log.info("🔍 [Azure Scheduler] Processing account: {} ({})",
                        account.getAccountName(), account.getAzureSubscriptionId());

                List<AzureAutoSpottingService.AzureAutoSpottingGroupDto> vmssList =
                        azureAutoSpottingService.listVmssAllRegionsWithCosts(account.getAzureSubscriptionId());

                List<AzureAutoSpottingService.AzureAutoSpottingGroupDto> enabledVmss = vmssList.stream()
                        .filter(vmss -> vmss.isEnabled)
                        .toList();

                if (enabledVmss.isEmpty()) {
                    log.debug("⏭️  [Azure Scheduler] No enabled VMSS for account {}, skipping",
                            account.getAzureSubscriptionId());
                    continue;
                }

                double accountDailySavings = enabledVmss.stream()
                        .mapToDouble(vmss -> vmss.actualHourlySavings * 24)
                        .sum();

                if (accountDailySavings <= 0.0) {
                    log.debug("💰 [Azure Scheduler] No savings for account {}, skipping",
                            account.getAzureSubscriptionId());
                    continue;
                }

                DailySpotSavings dailySavings = savingsRepository
                        .findByAccountIdAndDate(account.getId(), today)
                        .orElseGet(() -> new DailySpotSavings(account, today));

                dailySavings.setAmountSaved(accountDailySavings);
                dailySavings.setSwapCount(enabledVmss.size());
                savingsRepository.save(dailySavings);

                accountsProcessed++;
                vmssProcessed += enabledVmss.size();
                totalSavings += accountDailySavings;

                log.info("✅ [Azure Scheduler] Saved ${} for account {} ({} enabled VMSS)",
                        String.format("%.2f", accountDailySavings),
                        account.getAccountName(),
                        enabledVmss.size());

            } catch (Exception e) {
                log.error("❌ [Azure Scheduler] Failed to process account {}: {}",
                        account.getAzureSubscriptionId(), e.getMessage(), e);
            }
        }

        log.info("✅ [Azure AutoSpotting Scheduler] Daily savings calculation complete!");
        log.info("📊 Summary: {} accounts, {} VMSS, total savings: ${}",
                accountsProcessed, vmssProcessed, String.format("%.2f", totalSavings));
    }
}
