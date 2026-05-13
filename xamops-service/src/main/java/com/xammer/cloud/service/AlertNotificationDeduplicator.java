package com.xammer.cloud.service;

import com.xammer.cloud.dto.AlertDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.YearMonth;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Prevents sending duplicate alert notification emails.
 *
 * Digest alerts (AWS / GCP): content-fingerprint approach — the email is only
 * sent when the set of active alerts actually changes. The fingerprint of the
 * last-sent alert set is stored in Redis indefinitely (1-year TTL); as long as
 * the alerts are identical, no email is sent — regardless of how many days pass.
 *
 * Budget alerts: threshold-breach approach — one email per threshold per account
 * per calendar month (TTL until month-end).
 *
 * Redis keys:
 *   notif::aws-digest-fp::{accountId}           → last-sent fingerprint, TTL 1 year
 *   notif::gcp-digest-fp::{projectId}           → last-sent fingerprint, TTL 1 year
 *   notif::budget::{accountId}::{name}::{pct}::{yearMonth}  → TTL until month-end
 */
@Service
public class AlertNotificationDeduplicator {

    private static final Logger logger = LoggerFactory.getLogger(AlertNotificationDeduplicator.class);

    private static final int ONE_YEAR_MINUTES = 365 * 24 * 60;

    private final RedisCacheService redisCache;

    public AlertNotificationDeduplicator(RedisCacheService redisCache) {
        this.redisCache = redisCache;
    }

    // ── AWS digest ────────────────────────────────────────────────────────────

    public boolean awsDigestAlreadySentForContent(String accountId, String fingerprint) {
        Optional<String> stored = redisCache.get(awsDigestFpKey(accountId), String.class);
        return stored.map(fp -> fp.equals(fingerprint)).orElse(false);
    }

    public void markAwsDigestSent(String accountId, String fingerprint) {
        redisCache.put(awsDigestFpKey(accountId), fingerprint, ONE_YEAR_MINUTES);
        logger.info("[Dedup] Marked AWS digest sent for account {} (fp={}...)", accountId, fingerprint.substring(0, 8));
    }

    // ── GCP digest ────────────────────────────────────────────────────────────

    public boolean gcpDigestAlreadySentForContent(String projectId, String fingerprint) {
        Optional<String> stored = redisCache.get(gcpDigestFpKey(projectId), String.class);
        return stored.map(fp -> fp.equals(fingerprint)).orElse(false);
    }

    public void markGcpDigestSent(String projectId, String fingerprint) {
        redisCache.put(gcpDigestFpKey(projectId), fingerprint, ONE_YEAR_MINUTES);
        logger.info("[Dedup] Marked GCP digest sent for project {} (fp={}...)", projectId, fingerprint.substring(0, 8));
    }

    // ── Budget alerts ─────────────────────────────────────────────────────────

    public boolean budgetAlertAlreadySent(String accountId, String budgetName, int thresholdPct) {
        return redisCache.hasKey(budgetKey(accountId, budgetName, thresholdPct));
    }

    public void markBudgetAlertSent(String accountId, String budgetName, int thresholdPct) {
        int ttlMinutes = minutesUntilMonthEnd();
        redisCache.setFlag(budgetKey(accountId, budgetName, thresholdPct), ttlMinutes);
        logger.info("[Dedup] Marked budget alert sent: account={} budget={} threshold={}% (TTL {}min)",
                accountId, budgetName, thresholdPct, ttlMinutes);
    }

    // ── Fingerprint ───────────────────────────────────────────────────────────

    /**
     * Computes a stable SHA-256 fingerprint for a set of alerts.
     * Two alert lists produce the same fingerprint iff they contain the exact
     * same alerts (by type, name, service, region, status) regardless of order.
     */
    public static String computeFingerprint(List<AlertDto> alerts) {
        String combined = alerts.stream()
                .map(a -> String.join("|",
                        nullSafe(a.getType()),
                        nullSafe(a.getName()),
                        nullSafe(a.getService()),
                        nullSafe(a.getRegion()),
                        nullSafe(a.getStatus())))
                .sorted()
                .collect(Collectors.joining(","));
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(combined.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(combined.hashCode());
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private String awsDigestFpKey(String accountId) {
        return "notif::aws-digest-fp::" + accountId;
    }

    private String gcpDigestFpKey(String projectId) {
        return "notif::gcp-digest-fp::" + projectId;
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

    private static String nullSafe(String s) {
        return s != null ? s : "";
    }
}
