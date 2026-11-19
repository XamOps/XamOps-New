package com.xammer.cloud.service.autospotting.spi;

/**
 * The names and output keys the core reads from a FALLBACK_ENTER program (design-aws.md §6.4, design-core.md
 * §2.2). A provider returns outcomes and never sets a group's state, so its two fallback steps report what they
 * saw and the runner applies the lifecycle change, in the same fenced transaction as the group row.
 *
 * <ul>
 *   <li>{@link #UPDATE_DISTRIBUTION}: the mutating step; its {@code intent_at} is the billing end.</li>
 *   <li>{@link #VERIFY_FALLBACK_APPLIED}: Done output carries {@link #FINGERPRINT} (the live managed-field
 *       fingerprint to store as {@code fallback_fingerprint}).</li>
 *   <li>{@link #WAIT_CAPACITY}: Done or InProgress output carries {@link #CAPACITY} ({@code OK} or
 *       {@code SHORT}) and {@link #SHORT_SINCE}; the runner applies FALLBACK to DEGRADED once the group has
 *       been short for the still-short window, and DEGRADED to FALLBACK on recovery.</li>
 * </ul>
 */
public final class FallbackSteps {
    public static final String UPDATE_DISTRIBUTION = "UPDATE_DISTRIBUTION";
    public static final String VERIFY_FALLBACK_APPLIED = "VERIFY_FALLBACK_APPLIED";
    public static final String WAIT_CAPACITY = "WAIT_CAPACITY";
    /** A provider whose live hash includes the update policy (GCP) refreshes the stored fingerprint when this step is done. */
    public static final String RESTORE_UPDATE_POLICY = "RESTORE_UPDATE_POLICY";
    public static final String FINGERPRINT = "fallbackFingerprint";
    public static final String CAPACITY = "capacity";
    public static final String SHORT_SINCE = "shortSince";
    public static final String OK = "OK";
    public static final String SHORT = "SHORT";

    private FallbackSteps() {
    }
}
