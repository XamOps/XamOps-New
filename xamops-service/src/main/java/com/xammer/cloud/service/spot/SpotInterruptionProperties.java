package com.xammer.cloud.service.spot;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Spot interruption history configuration, under {@code xamops.k8s.spot-interruptions.*}
 * (design §B4.6).
 *
 * <p>Same style as {@code ClusterCostSnapshotProperties}: one {@code @Value}-bound constructor with
 * a {@code :} default on every field, so an absent property, an empty profile and a missing profile
 * all read as the documented default. None of the keys is declared in any profile file.
 * Out-of-range values are clamped rather than rejected, so a typo can never stop the application.
 *
 * <p>{@link #recordingEnabled()} is a kill switch for recording only. Recording derives rows from
 * data agents already pushed and makes no external call; it is not a readiness key and not an
 * {@code ENABLE_*} flag, and it gates no automation action.
 */
@Component
public class SpotInterruptionProperties {

    public static final String RECORDING_ENABLED_KEY = "xamops.k8s.spot-interruptions.recording.enabled";
    public static final String RETENTION_DAYS_KEY = "xamops.k8s.spot-interruptions.retention-days";
    public static final String REMOVAL_MAX_GAP_MINUTES_KEY = "xamops.k8s.spot-interruptions.removal-max-gap-minutes";
    public static final String RECOVERY_WINDOW_HOURS_KEY = "xamops.k8s.spot-interruptions.recovery-window-hours";
    public static final String REPORT_MAX_AGE_MINUTES_KEY = "xamops.k8s.spot-interruptions.report-max-age-minutes";

    private final boolean recordingEnabled;
    private final int retentionDays;
    private final int removalMaxGapMinutes;
    private final int recoveryWindowHours;
    private final int reportMaxAgeMinutes;

    public SpotInterruptionProperties(
            @Value("${" + RECORDING_ENABLED_KEY + ":true}") boolean recordingEnabled,
            @Value("${" + RETENTION_DAYS_KEY + ":365}") int retentionDays,
            @Value("${" + REMOVAL_MAX_GAP_MINUTES_KEY + ":15}") int removalMaxGapMinutes,
            @Value("${" + RECOVERY_WINDOW_HOURS_KEY + ":6}") int recoveryWindowHours,
            @Value("${" + REPORT_MAX_AGE_MINUTES_KEY + ":10}") int reportMaxAgeMinutes) {
        this.recordingEnabled = recordingEnabled;
        this.retentionDays = clamp(retentionDays, 30, 730);
        this.removalMaxGapMinutes = clamp(removalMaxGapMinutes, 5, 60);
        this.recoveryWindowHours = clamp(recoveryWindowHours, 1, 48);
        this.reportMaxAgeMinutes = clamp(reportMaxAgeMinutes, 3, 60);
    }

    /** The documented defaults (365 d, 15 min, 6 h, 10 min, recording on). */
    public static SpotInterruptionProperties defaults() {
        return new SpotInterruptionProperties(true, 365, 15, 6, 10);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Kill switch for recording. While false the tracker returns at once. */
    public boolean recordingEnabled() {
        return recordingEnabled;
    }

    /** Rows older than this (by noticed_at) are purged. Default 365, clamped 30..730. */
    public int retentionDays() {
        return retentionDays;
    }

    /** Longest gap between two snapshots that removal inference trusts. Default 15, clamped 5..60. */
    public int removalMaxGapMinutes() {
        return removalMaxGapMinutes;
    }

    /** How long a row is tracked for recovery after its notice. Default 6, clamped 1..48. */
    public int recoveryWindowHours() {
        return recoveryWindowHours;
    }

    /** Oldest node-optimizer-report still read as current. Default 10, clamped 3..60. */
    public int reportMaxAgeMinutes() {
        return reportMaxAgeMinutes;
    }

    public Duration removalMaxGap() {
        return Duration.ofMinutes(removalMaxGapMinutes);
    }

    public Duration recoveryWindow() {
        return Duration.ofHours(recoveryWindowHours);
    }

    /** {@code REPORT_MAX_AGE} of design §B5 / §B5.1. */
    public Duration reportMaxAge() {
        return Duration.ofMinutes(reportMaxAgeMinutes);
    }

    public Duration retention() {
        return Duration.ofDays(retentionDays);
    }
}
