package com.xammer.billops.service;

import com.xammer.billops.config.BillopsSchedulingEnvironmentConfig;
import com.xammer.billops.domain.CloudAccount;
import com.xammer.billops.repository.CloudAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Keeps CUR aggregates current for every AWS account that has an export configured.
 *
 * Cadence reasoning: AWS re-delivers a CUR export up to three times a day, and a
 * closed month is only final ~72h after the period ends. Polling every six hours
 * therefore picks up changes about as fast as they can occur, while
 * CurIngestionService's assemblyId check means an unchanged month costs one
 * ListObjectsV2 and nothing else — no download, no parse.
 *
 * Window reasoning: it re-checks the current month plus the previous two rather
 * than only the last closed month. The current month is refreshed because its
 * partial data still feeds the daily trend; the two prior months because AWS
 * restates a closed period during finalisation and occasionally later for credits
 * or refunds, and a restatement we never re-read would leave BillOps showing an
 * invoice total that no longer matches the bill.
 */
@Service
public class CurSnapshotScheduler {

    private static final Logger logger = LoggerFactory.getLogger(CurSnapshotScheduler.class);

    /** How many months back to re-check on each sweep, including the current one. */
    private static final int LOOKBACK_MONTHS = 3;

    private final CloudAccountRepository cloudAccountRepository;
    private final CurIngestionService curIngestionService;
    private final BillopsSchedulingEnvironmentConfig envConfig;

    @Value("${billops.cur.enabled:true}")
    private boolean curEnabled;

    /**
     * How far back a first-time backfill reaches, in months.
     *
     * Bounded deliberately. A newly-configured export only contains periods AWS has
     * actually delivered, so reaching further back than this just lists prefixes that
     * do not exist; and each month that DOES exist is a full download and parse, which
     * is not something to do unbounded on a startup path.
     */
    @Value("${billops.cur.backfill-months:6}")
    private int backfillMonths;

    public CurSnapshotScheduler(CloudAccountRepository cloudAccountRepository,
                                CurIngestionService curIngestionService,
                                BillopsSchedulingEnvironmentConfig envConfig) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.curIngestionService = curIngestionService;
        this.envConfig = envConfig;
    }

    /**
     * Rolling refresh of the recent window.
     *
     * Runs every 6 hours rather than on a fixed clock time so a restart does not
     * have to wait until tomorrow to pick up a newly-onboarded account. The initial
     * delay lets the app finish starting — the DB pool is already contended during
     * startup and this job is never urgent.
     */
    @Scheduled(fixedDelay = 6 * 60 * 60 * 1000L, initialDelay = 5 * 60 * 1000L)
    public void refreshRecentMonths() {
        if (!curEnabled) {
            logger.debug("[CUR] Ingestion disabled (billops.cur.enabled=false), skipping sweep");
            return;
        }
        if (!envConfig.isSchedulingEnabled()) {
            logger.debug("[CUR] Scheduling disabled for this environment, skipping sweep");
            return;
        }

        discoverNewExports();

        List<CloudAccount> accounts = curConfiguredAccounts();
        if (accounts.isEmpty()) {
            logger.debug("[CUR] No accounts have a CUR export configured");
            return;
        }

        YearMonth current = YearMonth.now();
        int ingested = 0;

        for (CloudAccount account : accounts) {
            for (int i = 0; i < LOOKBACK_MONTHS; i++) {
                YearMonth ym = current.minusMonths(i);
                try {
                    // force=false: the assemblyId check inside makes an unchanged
                    // month nearly free, which is what allows a 6-hourly cadence
                    // across every account without re-parsing the same data.
                    if (curIngestionService.ingestMonth(account, ym, false).isPresent()) {
                        ingested++;
                    }
                } catch (Exception e) {
                    // Never let one account's broken export stop the sweep — the rest
                    // still refresh, and this account keeps its Cost Explorer fallback.
                    logger.warn("[CUR] Sweep failed for {} {}: {}",
                            account.getAwsAccountId(), ym, e.getMessage());
                }
            }
        }

        logger.info("[CUR] Sweep complete — {} account(s) checked, {} month(s) (re)ingested",
                accounts.size(), ingested);
    }

    /**
     * One-off backfill for an account, used right after onboarding so a new customer
     * sees reconciled history immediately instead of waiting for the sweep.
     *
     * Returns a per-month summary rather than throwing, so the caller can surface
     * partial success — the common real outcome, since AWS has not yet delivered
     * every month a brand-new export was asked for.
     */
    public Map<String, String> backfillAccount(CloudAccount account) {
        Map<String, String> summary = new LinkedHashMap<>();
        if (!curIngestionService.isConfigured(account)) {
            summary.put("status", "no CUR export configured");
            return summary;
        }

        YearMonth current = YearMonth.now();
        for (int i = 0; i < Math.max(1, backfillMonths); i++) {
            YearMonth ym = current.minusMonths(i);
            try {
                boolean done = curIngestionService.ingestMonth(account, ym, false).isPresent();
                summary.put(ym.toString(), done ? "ingested" : "no export data / already current");
            } catch (Exception e) {
                summary.put(ym.toString(), "failed: " + e.getMessage());
            }
        }
        return summary;
    }

    /**
     * Picks up accounts that gained a CUR export after they were onboarded.
     *
     * Without this, an existing customer who re-runs their onboarding stack to get
     * the CUR bucket would stay on Cost Explorer forever, because detection
     * otherwise only happens during account verification — a step they have already
     * completed and will not repeat. One cheap probe per unconfigured account per
     * sweep closes that gap with no manual intervention.
     */
    private void discoverNewExports() {
        List<CloudAccount> unconfigured;
        try {
            unconfigured = cloudAccountRepository.findAll().stream()
                    .filter(a -> "AWS".equalsIgnoreCase(a.getProvider()))
                    .filter(a -> a.getAwsAccountId() != null && !a.getAwsAccountId().isBlank())
                    .filter(a -> a.getCurS3Bucket() == null || a.getCurS3Bucket().isBlank())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            logger.warn("[CUR] Could not scan for new exports: {}", e.getMessage());
            return;
        }

        for (CloudAccount account : unconfigured) {
            try {
                curIngestionService.detectCurConfig(account).ifPresent(config -> {
                    account.setCurS3Bucket(config[0]);
                    account.setCurReportPath(config[1]);
                    cloudAccountRepository.save(account);
                    logger.info("[CUR] Newly detected export for account {} — now reconciling from s3://{}/{}",
                            account.getAwsAccountId(), config[0], config[1]);
                });
            } catch (Exception e) {
                logger.debug("[CUR] Probe failed for {}: {}", account.getAwsAccountId(), e.getMessage());
            }
        }
    }

    /** AWS accounts with a CUR bucket recorded against them. */
    private List<CloudAccount> curConfiguredAccounts() {
        try {
            return cloudAccountRepository.findAll().stream()
                    .filter(a -> "AWS".equalsIgnoreCase(a.getProvider()))
                    .filter(curIngestionService::isConfigured)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            logger.warn("[CUR] Could not list accounts for sweep: {}", e.getMessage());
            return new ArrayList<>();
        }
    }
}
