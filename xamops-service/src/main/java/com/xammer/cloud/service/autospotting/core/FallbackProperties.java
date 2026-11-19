package com.xammer.cloud.service.autospotting.core;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The fallback knobs (design-aws.md §6.3, §6.5, plan-phase-20.md "Flag or rollout gate"), all under
 * {@code autospotting.aws.fallback.*} and tuned by the sandbox runbook before the AWS flag lift. Provider
 * neutral so the GCP rules reuse the same cap and backoff. One {@code @Value} constructor with a default on
 * every field, like the other AutoSpotting property holders.
 */
@Component
public class FallbackProperties {
    private final Duration enterWait;
    private final int maxTransitionsPerDay;
    private final Duration dwell;
    private final int minScore;
    private final List<Duration> backoff;
    private final Duration shortfall;
    private final int launchFailures;
    private final Duration failureWindow;
    private final int stormMin;
    private final double stormFraction;
    private final Duration stormWindow;
    private final Duration stillShort;
    private final Duration reentryWindow;
    private final Duration tailWaitMax;
    /** The GCP view ({@code autospotting.gcp.fallback.*}); null on the GCP view itself. */
    private final FallbackProperties gcp;
    @org.springframework.beans.factory.annotation.Autowired
    public FallbackProperties(
            @Value("${autospotting.aws.fallback.enter-wait:30m}") Duration enterWait,
            @Value("${autospotting.aws.fallback.max-transitions-per-day:5}") int maxTransitionsPerDay,
            @Value("${autospotting.aws.fallback.dwell:60m}") Duration dwell,
            @Value("${autospotting.aws.fallback.min-score:7}") int minScore,
            @Value("${autospotting.aws.fallback.backoff-minutes:60,120,240,480}") String backoffMinutes,
            @Value("${autospotting.aws.fallback.shortfall:10m}") Duration shortfall,
            @Value("${autospotting.aws.fallback.launch-failures:3}") int launchFailures,
            @Value("${autospotting.aws.fallback.failure-window:15m}") Duration failureWindow,
            @Value("${autospotting.aws.fallback.storm-min:3}") int stormMin,
            @Value("${autospotting.aws.fallback.storm-fraction:0.30}") double stormFraction,
            @Value("${autospotting.aws.fallback.storm-window:30m}") Duration stormWindow,
            @Value("${autospotting.aws.fallback.still-short:20m}") Duration stillShort,
            @Value("${autospotting.aws.fallback.reentry-window:30m}") Duration reentryWindow,
            @Value("${autospotting.gcp.fallback.max-transitions-per-day:5}") int gcpMaxTransitionsPerDay,
            @Value("${autospotting.gcp.fallback.dwell:60m}") Duration gcpDwell,
            @Value("${autospotting.gcp.fallback.backoff-minutes:60,120,240,480}") String gcpBackoffMinutes,
            @Value("${autospotting.gcp.fallback.still-short:20m}") Duration gcpStillShort,
            @Value("${autospotting.gcp.fallback.reentry-window:30m}") Duration gcpReentryWindow,
            @Value("${autospotting.gcp.fallback.tail-wait-max:2h}") Duration gcpTailWaitMax) {
        this.enterWait = enterWait;
        // The cap must leave room for one enter and one exit, otherwise no group could ever leave FALLBACK.
        this.maxTransitionsPerDay = Math.max(2, maxTransitionsPerDay);
        this.dwell = dwell;
        this.minScore = minScore;
        this.backoff = parse(backoffMinutes);
        this.shortfall = shortfall;
        this.launchFailures = Math.max(1, launchFailures);
        this.failureWindow = failureWindow;
        this.stormMin = Math.max(1, stormMin);
        this.stormFraction = stormFraction;
        this.stormWindow = stormWindow;
        this.stillShort = stillShort;
        this.reentryWindow = reentryWindow;
        this.tailWaitMax = Duration.ofHours(2);
        this.gcp = new FallbackProperties(this, gcpMaxTransitionsPerDay, gcpDwell, parse(gcpBackoffMinutes),
                gcpStillShort, gcpReentryWindow, gcpTailWaitMax);
    }
    /** The GCP view: the same knobs read from {@code autospotting.gcp.fallback.*} (design-gcp.md §7.2, DC-07). */
    private FallbackProperties(FallbackProperties base, int cap, Duration dwell, List<Duration> backoff,
                               Duration stillShort, Duration reentryWindow, Duration tailWaitMax) {
        this.enterWait = base.enterWait;
        this.maxTransitionsPerDay = Math.max(2, cap);
        this.dwell = dwell;
        this.minScore = base.minScore;
        this.backoff = backoff;
        this.shortfall = base.shortfall;
        this.launchFailures = base.launchFailures;
        this.failureWindow = base.failureWindow;
        this.stormMin = base.stormMin;
        this.stormFraction = base.stormFraction;
        this.stormWindow = base.stormWindow;
        this.stillShort = stillShort;
        this.reentryWindow = reentryWindow;
        this.tailWaitMax = tailWaitMax;
        this.gcp = null;
    }
    /** The documented defaults (tests and non-Spring callers). */
    public static FallbackProperties defaults() {
        return withCap(5);
    }
    /** The defaults with a different daily cap (both clouds). */
    public static FallbackProperties withCap(int cap) {
        return new FallbackProperties(Duration.ofMinutes(30), cap, Duration.ofMinutes(60), 7, "60,120,240,480",
                Duration.ofMinutes(10), 3, Duration.ofMinutes(15), 3, 0.30, Duration.ofMinutes(30),
                Duration.ofMinutes(20), Duration.ofMinutes(30), cap, Duration.ofMinutes(60), "60,120,240,480",
                Duration.ofMinutes(20), Duration.ofMinutes(30), Duration.ofHours(2));
    }
    /** The knobs of {@code provider}: AWS is this instance, GCP its {@code autospotting.gcp.fallback.*} view. */
    public FallbackProperties forProvider(com.xammer.cloud.domain.autospotting.Provider provider) {
        return provider == com.xammer.cloud.domain.autospotting.Provider.GCP && gcp != null ? gcp : this;
    }
    private static List<Duration> parse(String minutes) {
        List<Duration> out = new ArrayList<>();
        for (String part : (minutes == null ? "" : minutes).split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                try {
                    out.add(Duration.ofMinutes(Math.max(1, Long.parseLong(t))));
                } catch (NumberFormatException ignored) {
                    // a malformed entry is skipped; the defaults below apply when nothing parses
                }
            }
        }
        if (out.isEmpty()) {
            out = List.of(Duration.ofHours(1), Duration.ofHours(2), Duration.ofHours(4), Duration.ofHours(8));
        }
        return List.copyOf(out);
    }

    public Duration enterWait() { return enterWait; }
    public int maxTransitionsPerDay() { return maxTransitionsPerDay; }
    public Duration dwell() { return dwell; }
    public int minScore() { return minScore; }
    public List<Duration> backoff() { return backoff; }
    public Duration shortfall() { return shortfall; }
    public int launchFailures() { return launchFailures; }
    public Duration failureWindow() { return failureWindow; }
    public int stormMin() { return stormMin; }
    public double stormFraction() { return stormFraction; }
    public Duration stormWindow() { return stormWindow; }
    public Duration stillShort() { return stillShort; }
    public Duration reentryWindow() { return reentryWindow; }
    /** GCP only: how long a FALLBACK_ENTER waits for standard capacity before ending CAPACITY_PENDING (DR-24). */
    public Duration tailWaitMax() { return tailWaitMax; }
    public FallbackCap cap() { return new FallbackCap(maxTransitionsPerDay); }
}
