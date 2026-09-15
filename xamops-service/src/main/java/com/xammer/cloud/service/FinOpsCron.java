package com.xammer.cloud.service;

import com.xammer.cloud.dto.FinOpsReportScheduleDto;

/**
 * Cron expressions for FinOps report schedules.
 *
 * <p>
 * Extracted so the AWS and Azure schedule services derive the same value for the same frequency.
 * They did not: the Azure path carried {@code // TODO: You still need to set the cronExpression
 * field here before saving} and left the column unset. Because {@code cron_expression} is
 * {@code NOT NULL}, that did not produce a schedule that quietly never fired — it made every
 * attempt to create an Azure FinOps schedule fail on insert.
 *
 * <p>
 * Worth knowing before changing anything here: <strong>nothing parses these strings.</strong>
 * {@link FinOpsReportSchedulingService} dispatches reports by querying
 * {@code findAllActiveByFrequencyWithDetails(Frequency)}, so delivery is driven by the frequency
 * enum and the cron column is descriptive only. That is why the two generators below disagree on
 * field count — {@link #forFrequency} emits five fields and {@link #forCustom} emits six — and
 * why that discrepancy has never surfaced. Both are reproduced exactly as they were rather than
 * normalised, because the stored values are already in customer databases and "tidying" them
 * would be a silent data change for no behavioural gain. If something ever does start parsing
 * this column, normalise it deliberately and migrate.
 */
public final class FinOpsCron {

    private FinOpsCron() {
    }

    /**
     * Cron for a fixed frequency. Times are 12:30 UTC (6:00 PM IST).
     *
     * @throws IllegalArgumentException on an unrecognised frequency
     */
    public static String forFrequency(String frequency) {
        String value = frequency == null ? "" : frequency.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (value) {
            case "DAILY" -> "30 12 * * ?";      // 6:00 PM IST daily
            case "WEEKLY" -> "30 12 ? * FRI";   // 6:00 PM IST every Friday
            case "MONTHLY" -> "30 12 30 * ?";   // 6:00 PM IST on the 30th
            default -> throw new IllegalArgumentException("Invalid frequency: " + frequency);
        };
    }

    /**
     * Cron from user-supplied custom time parameters.
     *
     * <p>
     * Format is {@code second minute hour day-of-month month day-of-week}.
     *
     * @throws IllegalArgumentException when the parameters are missing or out of range
     */
    public static String forCustom(FinOpsReportScheduleDto dto) {
        validateCustom(dto);

        int minute = dto.getCustomMinute();
        int hour = dto.getCustomHour();
        String freqType = dto.getCustomFrequencyType().toUpperCase(java.util.Locale.ROOT);

        switch (freqType) {
            case "DAILY":
                return String.format("0 %d %d * * ?", minute, hour);
            case "WEEKLY":
                // 1-7 (Mon-Sun) mapped to cron day names.
                String[] days = { "MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN" };
                return String.format("0 %d %d ? * %s", minute, hour, days[dto.getCustomDayOfWeek() - 1]);
            case "MONTHLY":
                return String.format("0 %d %d %d * ?", minute, hour, dto.getCustomDayOfMonth());
            default:
                throw new IllegalArgumentException("Invalid custom frequency type: " + freqType);
        }
    }

    /** Rejects custom parameters that would produce an unusable cron. */
    public static void validateCustom(FinOpsReportScheduleDto dto) {
        if (dto.getCustomHour() == null || dto.getCustomHour() < 0 || dto.getCustomHour() > 23) {
            throw new IllegalArgumentException("Custom hour must be between 0 and 23");
        }
        if (dto.getCustomMinute() == null || dto.getCustomMinute() < 0 || dto.getCustomMinute() > 59) {
            throw new IllegalArgumentException("Custom minute must be between 0 and 59");
        }
        if (dto.getCustomFrequencyType() == null || dto.getCustomFrequencyType().isEmpty()) {
            throw new IllegalArgumentException("Custom frequency type is required");
        }

        String freqType = dto.getCustomFrequencyType().toUpperCase(java.util.Locale.ROOT);
        if (freqType.equals("WEEKLY")
                && (dto.getCustomDayOfWeek() == null || dto.getCustomDayOfWeek() < 1
                        || dto.getCustomDayOfWeek() > 7)) {
            throw new IllegalArgumentException("Custom day of week must be between 1 (Monday) and 7 (Sunday)");
        }
        if (freqType.equals("MONTHLY")
                && (dto.getCustomDayOfMonth() == null || dto.getCustomDayOfMonth() < 1
                        || dto.getCustomDayOfMonth() > 31)) {
            throw new IllegalArgumentException("Custom day of month must be between 1 and 31");
        }
    }
}
