package com.xammer.cloud.service.autospotting.aws;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * The rollout-monitoring knobs (design-aws.md §2.5, plan-phase-18.md task 18.2):
 * {@code autospotting.aws.rollout.tolerance} (instances, default 1; the 10% rule applies when larger),
 * {@code .spot-wait-cap} (default 2 h) and {@code .shortfall-window} (default 10 min). A single
 * {@code @Value} constructor with a placeholder default on every field, like the other
 * AutoSpotting property holders.
 */
@Component
public class AwsRolloutProperties {

    /** Poll cadence of {@code WAIT_ROLLOUT}. */
    public static final Duration POLL = Duration.ofSeconds(60);
    /** The stall window a FALLBACK_EXIT uses (design-aws.md §2.5 "Capacity stall"). */
    public static final Duration FALLBACK_STALL_WINDOW = Duration.ofMinutes(30);
    /** Share of desired capacity the Spot count may differ from target by (10%). */
    public static final double TOLERANCE_FRACTION = 0.10;

    private final int tolerance;
    private final Duration spotWaitCap;
    private final Duration shortfallWindow;

    public AwsRolloutProperties(
            @Value("${autospotting.aws.rollout.tolerance:1}") int tolerance,
            @Value("${autospotting.aws.rollout.spot-wait-cap:2h}") Duration spotWaitCap,
            @Value("${autospotting.aws.rollout.shortfall-window:10m}") Duration shortfallWindow) {
        this.tolerance = Math.max(1, tolerance);
        this.spotWaitCap = spotWaitCap;
        this.shortfallWindow = shortfallWindow;
    }

    /** Test and default constructor values. */
    public static AwsRolloutProperties defaults() {
        return new AwsRolloutProperties(1, Duration.ofHours(2), Duration.ofMinutes(10));
    }

    public int tolerance() {
        return tolerance;
    }

    public Duration spotWaitCap() {
        return spotWaitCap;
    }

    public Duration shortfallWindow() {
        return shortfallWindow;
    }
}
