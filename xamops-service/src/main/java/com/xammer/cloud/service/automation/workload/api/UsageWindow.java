package com.xammer.cloud.service.automation.workload.api;

import com.xammer.cloud.service.automation.workload.usage.PrometheusRangeReader;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The span and step of one usage or HPA-history read.
 *
 * <p>The three fixed windows carry their own step (24 h at 10 min, 72 h at 30 min, 7 d at 1 h, design
 * v2 §5.2) and end at the server's current minute, so a point means the same instant on every read
 * within that minute and a cached answer is a consistent one. A custom window (the workload impact
 * chart) is {@code from}–{@code to} at one of {@link #CUSTOM_STEPS}, at most {@link #MAX_CUSTOM_SPAN}
 * long and at most {@link #MAX_POINTS} points per series.
 *
 * @param label       {@code 24h | 72h | 168h | custom}
 * @param hours       the fixed span; 0 for a custom window
 * @param from        a custom window's start; null for a fixed one
 * @param to          a custom window's end; null for a fixed one
 * @param stepSeconds the distance between points
 */
public record UsageWindow(String label, int hours, Instant from, Instant to, int stepSeconds) {

    public static final String CUSTOM = "custom";
    public static final List<Integer> CUSTOM_STEPS = List.of(60, 300, 1800, 3600);
    public static final Duration MAX_CUSTOM_SPAN = Duration.ofDays(7);
    public static final int MAX_POINTS = 600;
    /** How far past the server's clock a custom window may end: client clock skew, not the future. */
    public static final Duration FUTURE_TOLERANCE = Duration.ofSeconds(60);

    public UsageWindow {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("a window needs a label");
        }
        if (stepSeconds <= 0) {
            throw new IllegalArgumentException("a window needs a positive step");
        }
        if (CUSTOM.equals(label)) {
            if (from == null || to == null || !from.isBefore(to)) {
                throw new IllegalArgumentException("a custom window needs from < to");
            }
        } else if (hours <= 0 || from != null || to != null) {
            throw new IllegalArgumentException("a fixed window needs hours and no from/to");
        }
    }

    /** {@code 24h}, {@code 72h} or {@code 168h}; empty for anything else, matched exactly. */
    public static Optional<UsageWindow> fixed(String label) {
        if (label == null) {
            return Optional.empty();
        }
        return switch (label) {
            case "24h" -> Optional.of(new UsageWindow(label, 24, null, null, 600));
            case "72h" -> Optional.of(new UsageWindow(label, 72, null, null, 1800));
            case "168h" -> Optional.of(new UsageWindow(label, 168, null, null, 3600));
            default -> Optional.empty();
        };
    }

    /** A validated custom window; the controller checks the limits first and names the field. */
    public static UsageWindow custom(Instant from, Instant to, int stepSeconds) {
        return new UsageWindow(CUSTOM, 0, from, to, stepSeconds);
    }

    /** Points per series a custom window at {@code stepSeconds} would have. */
    public static long points(Instant from, Instant to, int stepSeconds) {
        return (to.getEpochSecond() - from.getEpochSecond()) / stepSeconds + 1;
    }

    public boolean isCustom() {
        return CUSTOM.equals(label);
    }

    /** The evaluation grid: a fixed window ends at {@code now}'s whole minute. */
    PrometheusRangeReader.Grid grid(Instant now) {
        if (isCustom()) {
            return new PrometheusRangeReader.Grid(from.getEpochSecond(), to.getEpochSecond(), stepSeconds);
        }
        long end = Math.floorDiv(now.getEpochSecond(), 60L) * 60L;
        return new PrometheusRangeReader.Grid(end - hours * 3600L, end, stepSeconds);
    }

    /** What distinguishes two windows in a cache key. */
    String cacheKey() {
        return isCustom()
                ? CUSTOM + "@" + from.getEpochSecond() + "-" + to.getEpochSecond() + "/" + stepSeconds
                : label + "/" + stepSeconds;
    }
}
