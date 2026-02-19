package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.DailySpotSavings;
import com.xammer.cloud.domain.SpotOptimizationEvent;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.DailySpotSavingsRepository;
import com.xammer.cloud.repository.SpotOptimizationEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Daily scheduler that aggregates Spot savings from SpotOptimizationEvent into
 * DailySpotSavings so the "Savings Over Time" chart accumulates real history.
 *
 * Runs at midnight — queries the DB only, makes zero AWS API calls.
 * This is safe and cheap compared to calling getCostData() (which can fall
 * back to Cost Explorer when the primary SDK path fails).
 */
@Component
public class AutoSpottingScheduler {

    private static final Logger logger = LoggerFactory.getLogger(AutoSpottingScheduler.class);

    private final CloudAccountRepository cloudAccountRepository;
    private final DailySpotSavingsRepository savingsRepository;
    private final SpotOptimizationEventRepository eventRepository;

    @Autowired
    public AutoSpottingScheduler(CloudAccountRepository cloudAccountRepository,
                                 DailySpotSavingsRepository savingsRepository,
                                 SpotOptimizationEventRepository eventRepository) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.savingsRepository = savingsRepository;
        this.eventRepository = eventRepository;
    }

    /**
     * Runs every midnight.  Aggregates SUCCESS events from the previous day
     * and upserts a DailySpotSavings row — purely from the local DB, no AWS calls.
     */
    @Scheduled(cron = "0 0 0 * * *")
    public void snapshotDailySavings() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        Instant dayStart = yesterday.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant dayEnd   = yesterday.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        logger.info("=== AutoSpotting savings snapshot for {} ===", yesterday);

        List<CloudAccount> accounts = cloudAccountRepository.findAll();
        int updated = 0;

        for (CloudAccount account : accounts) {
            if (account.getAwsAccountId() == null || account.getAwsAccountId().isBlank()) {
                continue;
            }

            try {
                List<SpotOptimizationEvent> events =
                        eventRepository.findByAccountIdAndEventTimeBetweenAndStatusOrderByEventTimeDesc(
                                account.getId(), dayStart, dayEnd, "SUCCESS");

                double hourlySavings = events.stream()
                        .mapToDouble(e -> e.getHourlySavings() != null ? e.getHourlySavings() : 0.0)
                        .sum();

                if (hourlySavings <= 0.0) {
                    continue; // nothing to record — skip so chart stays clean
                }

                DailySpotSavings row = savingsRepository
                        .findByAccountIdAndDate(account.getId(), yesterday)
                        .orElseGet(() -> new DailySpotSavings(account, yesterday));

                row.setAmountSaved(hourlySavings);
                row.setSwapCount(events.size());
                savingsRepository.save(row);
                updated++;

                logger.info("Snapshot saved account={} date={} hourlySavings={} swapCount={}",
                        account.getAwsAccountId(), yesterday, hourlySavings, events.size());

            } catch (Exception e) {
                logger.warn("Failed to snapshot savings for account {}: {}",
                        account.getAwsAccountId(), e.getMessage());
            }
        }

        logger.info("=== Snapshot complete — {} account(s) updated ===", updated);
    }
}
