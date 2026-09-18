package com.xammer.cloud.service;

import com.xammer.cloud.dto.AlertDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import com.fasterxml.jackson.core.type.TypeReference;

import java.time.YearMonth;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Prevents sending duplicate alert notification emails.
 *
 * Digest alerts (AWS / GCP / Azure): per-alert tracking. The set of alert keys last
 * notified is stored per account, and an email is sent only when that set gains a
 * member. A finding being resolved updates the stored set but sends nothing — the
 * account getting healthier is not news that warrants an alert email.
 *
 * This replaced a single set-level fingerprint, which changed on any membership
 * change in either direction. Two consequences on a real account with ~2000 GCP
 * alerts: a resolved finding re-sent the whole digest, and because the digest listed
 * every alert by name, each of those emails ran to two thousand rows.
 *
 * Budget alerts: threshold-breach approach — one email per threshold per account
 * per calendar month (TTL until month-end).
 *
 * Redis keys:
 *   notif::seen-alerts::{provider}::{accountId}  → keys last notified, TTL 1 year
 *   notif::aws-digest-fp::{accountId}            → legacy fingerprint, TTL 1 year
 *   notif::gcp-digest-fp::{projectId}            → legacy fingerprint, TTL 1 year
 *   notif::azure-digest-fp::{subscriptionId}     → legacy fingerprint, TTL 1 year
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

    // ── New-finding detection (all providers) ─────────────────────────────────

    /**
     * What changed since the last notification for an account.
     *
     * @param newAlerts alerts not present at the previous notification
     * @param totalOpen size of the current alert set, so the email can say "N total"
     *                  without re-listing all of it
     * @param coldStart no previous state existed — every alert looks new, which is a
     *                  different situation from genuinely-new and must be worded as a
     *                  baseline rather than as N new findings
     */
    public record AlertDelta(List<AlertDto> newAlerts, int totalOpen, boolean coldStart) {

        /** True when there is something worth emailing about. */
        public boolean worthSending() {
            return !newAlerts.isEmpty();
        }
    }

    /**
     * Compares the current alert set against the last notified set.
     *
     * <p>This replaces a single set-level fingerprint. That fingerprint changed whenever
     * <em>membership</em> changed in either direction, so an alert being <em>resolved</em>
     * re-sent the entire digest — the product improving generated an alert email. It also
     * meant that on an account with two thousand alerts, any one of them appearing or
     * clearing re-mailed all two thousand.
     *
     * <p>Keys deliberately exclude {@code usage} and {@code limit}, matching
     * {@link #computeFingerprint}: a quota alert drifting from 76% to 77% is the same
     * finding and must not read as a new one.
     */
    public AlertDelta detectNewAlerts(String provider, String accountId, List<AlertDto> current) {
        List<AlertDto> alerts = current != null ? current : List.of();
        String key = seenKey(provider, accountId);

        Optional<List<String>> stored = redisCache.get(key, new TypeReference<List<String>>() {
        });

        if (stored.isEmpty()) {
            // No prior state: a first run, or Redis was flushed. Reporting two thousand
            // "new" findings would be false — they are simply the ones we have not told
            // this account about yet.
            logger.info("[Dedup] No prior alert state for {} {} — treating {} alert(s) as a baseline",
                    provider, accountId, alerts.size());
            return new AlertDelta(alerts, alerts.size(), true);
        }

        Set<String> seen = new HashSet<>(stored.get());
        List<AlertDto> added = alerts.stream()
                .filter(a -> !seen.contains(alertKey(a)))
                .collect(Collectors.toList());

        long resolved = seen.size() - (alerts.size() - added.size());
        if (added.isEmpty() && resolved > 0) {
            logger.info("[Dedup] {} {}: {} alert(s) resolved, none new — no email", provider, accountId, resolved);
        }
        return new AlertDelta(added, alerts.size(), false);
    }

    /**
     * Records the current alert set as notified.
     *
     * <p>Must be called even when no email was sent, so that a resolved alert stops being
     * remembered and cannot re-appear as "new" later.
     */
    public void markSeen(String provider, String accountId, List<AlertDto> current) {
        List<String> keys = (current != null ? current : List.<AlertDto>of()).stream()
                .map(AlertNotificationDeduplicator::alertKey)
                .distinct()
                .collect(Collectors.toList());
        redisCache.put(seenKey(provider, accountId), keys, ONE_YEAR_MINUTES);
        logger.debug("[Dedup] Recorded {} alert key(s) as seen for {} {}", keys.size(), provider, accountId);
    }

    /** Forgets an account's notified set, so the next run re-baselines. */
    public void clearSeen(String provider, String accountId) {
        redisCache.evict(seenKey(provider, accountId));
    }

    /**
     * Stable identity for one alert.
     *
     * <p>Same fields as {@link #computeFingerprint} so the two cannot disagree about what
     * makes an alert "the same alert".
     */
    static String alertKey(AlertDto a) {
        return String.join("|",
                nullSafe(a.getType()),
                nullSafe(a.getName()),
                nullSafe(a.getService()),
                nullSafe(a.getRegion()),
                nullSafe(a.getStatus()));
    }

    private String seenKey(String provider, String accountId) {
        return "notif::seen-alerts::" + provider.toLowerCase(java.util.Locale.ROOT) + "::" + accountId;
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

    // ── Azure digest ──────────────────────────────────────────────────────────

    public boolean azureDigestAlreadySentForContent(String subscriptionId, String fingerprint) {
        Optional<String> stored = redisCache.get(azureDigestFpKey(subscriptionId), String.class);
        return stored.map(fp -> fp.equals(fingerprint)).orElse(false);
    }

    public void markAzureDigestSent(String subscriptionId, String fingerprint) {
        redisCache.put(azureDigestFpKey(subscriptionId), fingerprint, ONE_YEAR_MINUTES);
        logger.info("[Dedup] Marked Azure digest sent for subscription {} (fp={}...)", subscriptionId, fingerprint.substring(0, 8));
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

    private String azureDigestFpKey(String subscriptionId) {
        return "notif::azure-digest-fp::" + subscriptionId;
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
