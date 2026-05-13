package com.xammer.cloud.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

/**
 * Prevents the same alert notification from being sent more than once per day (digest alerts)
 * or more than once per threshold-breach per month (budget alerts).
 *
 * Keys stored in Redis:
 *   notif::aws-digest::{accountId}::{date}          TTL 23h
 *   notif::gcp-digest::{projectId}::{date}          TTL 23h
 *   notif::budget::{accountId}::{budgetName}::{pct}::{yearMonth}  TTL until month end
 */
@Service
public class AlertNotificationDeduplicator {

    private static final Logger logger = LoggerFactory.getLogger(AlertNotificationDeduplicator.class);

    private static final int DIGEST_TTL_MINUTES = 23 * 60; // 23 hours

    private final RedisCacheService redisCache;

    public AlertNotificationDeduplicator(RedisCacheService redisCache) {
        this.redisCache = redisCache;
    }

    public boolean awsDigestAlreadySentToday(String accountId) {
        return redisCache.hasKey(awsDigestKey(accountId));
    }

    public void markAwsDigestSent(String accountId) {
        redisCache.setFlag(awsDigestKey(accountId), DIGEST_TTL_MINUTES);
        logger.info("[Dedup] Marked AWS digest sent for account {} (TTL 23h)", accountId);
    }

    public boolean gcpDigestAlreadySentToday(String projectId) {
        return redisCache.hasKey(gcpDigestKey(projectId));
    }

    public void markGcpDigestSent(String projectId) {
        redisCache.setFlag(gcpDigestKey(projectId), DIGEST_TTL_MINUTES);
        logger.info("[Dedup] Marked GCP digest sent for project {} (TTL 23h)", projectId);
    }

    public boolean budgetAlertAlreadySent(String accountId, String budgetName, int thresholdPct) {
        return redisCache.hasKey(budgetKey(accountId, budgetName, thresholdPct));
    }

    public void markBudgetAlertSent(String accountId, String budgetName, int thresholdPct) {
        int ttlMinutes = minutesUntilMonthEnd();
        redisCache.setFlag(budgetKey(accountId, budgetName, thresholdPct), ttlMinutes);
        logger.info("[Dedup] Marked budget alert sent: account={} budget={} threshold={}% (TTL {}min)",
                accountId, budgetName, thresholdPct, ttlMinutes);
    }

    private String awsDigestKey(String accountId) {
        return "notif::aws-digest::" + accountId + "::" + LocalDate.now();
    }

    private String gcpDigestKey(String projectId) {
        return "notif::gcp-digest::" + projectId + "::" + LocalDate.now();
    }

    private String budgetKey(String accountId, String budgetName, int thresholdPct) {
        String safeName = budgetName.replaceAll("[^a-zA-Z0-9_-]", "_");
        return "notif::budget::" + accountId + "::" + safeName + "::" + thresholdPct + "::" + YearMonth.now();
    }

    private int minutesUntilMonthEnd() {
        LocalDate today = LocalDate.now();
        LocalDate endOfMonth = today.withDayOfMonth(today.lengthOfMonth());
        long days = ChronoUnit.DAYS.between(today, endOfMonth) + 1;
        return (int) Math.min(days * 24 * 60, Integer.MAX_VALUE);
    }
}
