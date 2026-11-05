package com.xammer.cloud.service.autospotting.core.lease;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Lease timings, under {@code autospotting.lease.*} (design-core.md §3.3). Same style as
 * {@link com.xammer.cloud.service.autospotting.AutoSpottingFeatureFlags} and
 * {@code UsageRollupProperties}: a single {@code @Value}-bound constructor with a {@code :}
 * placeholder default on every field, so an absent property, an empty property and a missing profile
 * all read as the safe default. Nothing is declared in any profile; the defaults below apply.
 *
 * <p>Durations are seconds. The group-lease invariant of §4.3 (a mutating call's 45 s per-call
 * timeout is below TTL minus two heartbeats) holds at the defaults: 120 − 2×30 = 60 &gt; 45. The
 * {@link #skewMargin()} is the 10 s margin {@link LeaseHandle#assertHeld} subtracts from the local
 * monotonic time left before a cloud call (§3.4).
 */
@Component
public class LeaseProperties {

    private static final Logger log = LoggerFactory.getLogger(LeaseProperties.class);

    public static final String PREFIX = "autospotting.lease.";

    private final Duration groupTtl;
    private final Duration groupHeartbeat;
    private final Duration reconcilerTtl;
    private final Duration reconcilerHeartbeat;
    private final Duration dailyTtl;
    private final Duration dailyHeartbeat;
    private final Duration dailyMinHold;
    private final Duration skewMargin;

    public LeaseProperties(
            @Value("${" + PREFIX + "group-ttl-seconds:120}") long groupTtlSeconds,
            @Value("${" + PREFIX + "group-heartbeat-seconds:30}") long groupHeartbeatSeconds,
            @Value("${" + PREFIX + "reconciler-ttl-seconds:600}") long reconcilerTtlSeconds,
            @Value("${" + PREFIX + "reconciler-heartbeat-seconds:60}") long reconcilerHeartbeatSeconds,
            @Value("${" + PREFIX + "daily-ttl-seconds:1800}") long dailyTtlSeconds,
            @Value("${" + PREFIX + "daily-heartbeat-seconds:60}") long dailyHeartbeatSeconds,
            @Value("${" + PREFIX + "daily-min-hold-seconds:300}") long dailyMinHoldSeconds,
            @Value("${" + PREFIX + "skew-margin-seconds:10}") long skewMarginSeconds) {
        this.groupTtl = Duration.ofSeconds(groupTtlSeconds);
        this.groupHeartbeat = Duration.ofSeconds(groupHeartbeatSeconds);
        this.reconcilerTtl = Duration.ofSeconds(reconcilerTtlSeconds);
        this.reconcilerHeartbeat = Duration.ofSeconds(reconcilerHeartbeatSeconds);
        this.dailyTtl = Duration.ofSeconds(dailyTtlSeconds);
        this.dailyHeartbeat = Duration.ofSeconds(dailyHeartbeatSeconds);
        this.dailyMinHold = Duration.ofSeconds(dailyMinHoldSeconds);
        this.skewMargin = Duration.ofSeconds(skewMarginSeconds);
        log.info("AutoSpotting lease timings: group ttl={}s hb={}s; reconciler ttl={}s hb={}s; "
                        + "daily ttl={}s hb={}s minHold={}s; skewMargin={}s",
                groupTtl.toSeconds(), groupHeartbeat.toSeconds(), reconcilerTtl.toSeconds(),
                reconcilerHeartbeat.toSeconds(), dailyTtl.toSeconds(), dailyHeartbeat.toSeconds(),
                dailyMinHold.toSeconds(), skewMargin.toSeconds());
    }

    public Duration groupTtl() {
        return groupTtl;
    }

    public Duration groupHeartbeat() {
        return groupHeartbeat;
    }

    public Duration reconcilerTtl() {
        return reconcilerTtl;
    }

    public Duration reconcilerHeartbeat() {
        return reconcilerHeartbeat;
    }

    public Duration dailyTtl() {
        return dailyTtl;
    }

    public Duration dailyHeartbeat() {
        return dailyHeartbeat;
    }

    public Duration dailyMinHold() {
        return dailyMinHold;
    }

    public Duration skewMargin() {
        return skewMargin;
    }
}
