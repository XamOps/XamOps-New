package com.xammer.cloud.dto.autospotting.v2;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Analytics view response (plan-phase-14.md task 14.2, PD-08, AC-EVT-06). Counts come from
 * {@code spot_group_event}. A metric the account has no event source for is {@code null} and named in
 * {@code notTracked}, never 0, so the view shows "Not tracked" (AC-CMP-05 spirit: absence is not zero).
 *
 * @param from       start of the range (inclusive)
 * @param to         end of the range (exclusive)
 * @param notTracked the metric names that have no source for this account
 * @param totals     the account-wide figures
 * @param groups     one entry per group with figures in the range, or tracked at all
 */
public record AnalyticsDto(Instant from, Instant to, List<String> notTracked, Figures totals,
                           List<GroupAnalytics> groups) {

    public static final String SPOT_LAUNCHES = "spotLaunches";
    public static final String LAUNCH_FAILURES = "launchFailures";
    public static final String INTERRUPTIONS = "interruptions";
    public static final String REBALANCE_RECOMMENDATIONS = "rebalanceRecommendations";
    public static final String SUCCESS_RATE = "successRate";

    public AnalyticsDto {
        notTracked = notTracked == null ? List.of() : List.copyOf(notTracked);
        groups = groups == null ? List.of() : List.copyOf(groups);
    }

    /**
     * The five figures. Counts are null when not tracked. {@code successRate} (0 to 100) is null when not
     * tracked or when there were no Spot launches and no failures to rate. {@code failuresByCause} keys
     * are the classified cause (SPOT_CAPACITY, SPOT_QUOTA, ON_DEMAND, OTHER).
     */
    public record Figures(Long spotLaunches, Long launchFailures, Map<String, Long> failuresByCause,
                          Long interruptions, Long rebalanceRecommendations, Double successRate) {
        public Figures {
            failuresByCause = failuresByCause == null ? Map.of() : Map.copyOf(failuresByCause);
        }
    }

    /** One group's figures and the same figures per instance type. */
    public record GroupAnalytics(UUID groupId, String groupKey, String name, String location, Figures figures,
                                 List<TypeFigures> byType) {
        public GroupAnalytics {
            byType = byType == null ? List.of() : List.copyOf(byType);
        }
    }

    /** Figures for one instance type of a group. */
    public record TypeFigures(String instanceType, Figures figures) {
    }
}
