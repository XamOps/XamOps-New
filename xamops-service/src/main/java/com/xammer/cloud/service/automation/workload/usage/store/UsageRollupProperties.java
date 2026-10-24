package com.xammer.cloud.service.automation.workload.usage.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The usage-rollup configuration, under {@code xamops.k8s.usage-rollup.*}, with its master switch
 * defaulting to <strong>off</strong>.
 *
 * <p>Same style as {@code ClusterCostSnapshotProperties}: a single {@code @Value}-bound constructor
 * with a {@code :} placeholder default on every field, so an absent property, an empty property and a
 * missing profile all read as the safe value. The master switch is declared {@code true} only in
 * {@code application-uat.properties}; the retention and cadence keys are never declared and take the
 * defaults below.
 *
 * <h2>Clamps</h2>
 *
 * A retention shorter than the window the jobs re-read would delete data before it is rolled up, so
 * the constructor raises such values to the smallest safe one and logs a WARN naming the key:
 *
 * <ul>
 *   <li>{@code hourly-retention-days >= max(7, daily-lookback-days + 2)}</li>
 *   <li>{@code daily-retention-days >= 31}</li>
 *   <li>{@code sample-retention-hours >= rollup-lookback-hours + 24}</li>
 *   <li>{@code premake-months} in {@code 1..12}</li>
 *   <li>{@code queue-capacity} in {@code 1..1000}</li>
 * </ul>
 */
@Component
public class UsageRollupProperties {

    private static final Logger logger = LoggerFactory.getLogger(UsageRollupProperties.class);

    public static final String PREFIX = "xamops.k8s.usage-rollup.";
    public static final String ENABLED_KEY = PREFIX + "enabled";
    public static final String HOURLY_RETENTION_DAYS_KEY = PREFIX + "hourly-retention-days";
    public static final String DAILY_RETENTION_DAYS_KEY = PREFIX + "daily-retention-days";
    public static final String SAMPLE_RETENTION_HOURS_KEY = PREFIX + "sample-retention-hours";
    public static final String PREMAKE_MONTHS_KEY = PREFIX + "premake-months";
    public static final String ROLLUP_LOOKBACK_HOURS_KEY = PREFIX + "rollup-lookback-hours";
    public static final String DAILY_LOOKBACK_DAYS_KEY = PREFIX + "daily-lookback-days";
    public static final String QUEUE_CAPACITY_KEY = PREFIX + "queue-capacity";
    public static final String HOURLY_CRON_KEY = PREFIX + "hourly-cron";
    public static final String MAINTENANCE_CRON_KEY = PREFIX + "maintenance-cron";

    /** Defaults, also used by the {@code @Scheduled} placeholders. */
    public static final String DEFAULT_HOURLY_CRON = "0 7 * * * *";
    public static final String DEFAULT_MAINTENANCE_CRON = "0 30 1 * * *";

    static final int MIN_HOURLY_RETENTION_DAYS = 7;
    static final int MIN_DAILY_RETENTION_DAYS = 31;
    static final int MAX_PREMAKE_MONTHS = 12;
    static final int MAX_QUEUE_CAPACITY = 1000;

    private final boolean enabled;
    private final int hourlyRetentionDays;
    private final int dailyRetentionDays;
    private final int sampleRetentionHours;
    private final int premakeMonths;
    private final int rollupLookbackHours;
    private final int dailyLookbackDays;
    private final int queueCapacity;
    private final String hourlyCron;
    private final String maintenanceCron;

    public UsageRollupProperties(
            @Value("${" + ENABLED_KEY + ":false}") boolean enabled,
            @Value("${" + HOURLY_RETENTION_DAYS_KEY + ":90}") int hourlyRetentionDays,
            @Value("${" + DAILY_RETENTION_DAYS_KEY + ":730}") int dailyRetentionDays,
            @Value("${" + SAMPLE_RETENTION_HOURS_KEY + ":48}") int sampleRetentionHours,
            @Value("${" + PREMAKE_MONTHS_KEY + ":2}") int premakeMonths,
            @Value("${" + ROLLUP_LOOKBACK_HOURS_KEY + ":3}") int rollupLookbackHours,
            @Value("${" + DAILY_LOOKBACK_DAYS_KEY + ":3}") int dailyLookbackDays,
            @Value("${" + QUEUE_CAPACITY_KEY + ":50}") int queueCapacity,
            @Value("${" + HOURLY_CRON_KEY + ":" + DEFAULT_HOURLY_CRON + "}") String hourlyCron,
            @Value("${" + MAINTENANCE_CRON_KEY + ":" + DEFAULT_MAINTENANCE_CRON + "}") String maintenanceCron) {
        this.enabled = enabled;
        this.rollupLookbackHours = rollupLookbackHours;
        this.dailyLookbackDays = dailyLookbackDays;
        this.hourlyRetentionDays = atLeast(HOURLY_RETENTION_DAYS_KEY, hourlyRetentionDays,
                Math.max(MIN_HOURLY_RETENTION_DAYS, dailyLookbackDays + 2));
        this.dailyRetentionDays = atLeast(DAILY_RETENTION_DAYS_KEY, dailyRetentionDays, MIN_DAILY_RETENTION_DAYS);
        this.sampleRetentionHours = atLeast(SAMPLE_RETENTION_HOURS_KEY, sampleRetentionHours,
                rollupLookbackHours + 24);
        this.premakeMonths = within(PREMAKE_MONTHS_KEY, premakeMonths, 1, MAX_PREMAKE_MONTHS);
        this.queueCapacity = within(QUEUE_CAPACITY_KEY, queueCapacity, 1, MAX_QUEUE_CAPACITY);
        this.hourlyCron = hourlyCron;
        this.maintenanceCron = maintenanceCron;
    }

    /** The master switch. While false nothing is captured, rolled up or maintained. */
    public boolean enabled() {
        return enabled;
    }

    /** How long hourly rows are kept (days). Default 90. */
    public int hourlyRetentionDays() {
        return hourlyRetentionDays;
    }

    /** How long daily rows are kept (days). Default 730. */
    public int dailyRetentionDays() {
        return dailyRetentionDays;
    }

    /** How long raw samples are kept (hours). Default 48. */
    public int sampleRetentionHours() {
        return sampleRetentionHours;
    }

    /** How many months ahead of the current one the maintenance job pre-creates. Default 2. */
    public int premakeMonths() {
        return premakeMonths;
    }

    /** How many closed hours the hourly job recomputes on each run. Default 3. */
    public int rollupLookbackHours() {
        return rollupLookbackHours;
    }

    /** How many closed UTC days the daily downsample recomputes on each run. Default 3. */
    public int dailyLookbackDays() {
        return dailyLookbackDays;
    }

    /** Bounded capture queue capacity. Default 50. */
    public int queueCapacity() {
        return queueCapacity;
    }

    /** Cron (UTC) of the hourly rollup job. Default {@value #DEFAULT_HOURLY_CRON}. */
    public String hourlyCron() {
        return hourlyCron;
    }

    /** Cron (UTC) of the daily maintenance job. Default {@value #DEFAULT_MAINTENANCE_CRON}. */
    public String maintenanceCron() {
        return maintenanceCron;
    }

    private static int atLeast(String key, int value, int min) {
        if (value < min) {
            logger.warn("{}={} is below the safe minimum {}; using {}", key, value, min, min);
            return min;
        }
        return value;
    }

    private static int within(String key, int value, int min, int max) {
        if (value < min || value > max) {
            int clamped = Math.max(min, Math.min(max, value));
            logger.warn("{}={} is outside {}..{}; using {}", key, value, min, max, clamped);
            return clamped;
        }
        return value;
    }
}
