package com.xammer.cloud.service.automation.workload.api.dto;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Changes per day ({@code GET /api/eks/automation/activity/{cluster}/summary}): the activity feed's
 * entries counted per India Standard Time calendar day, by the feed's own result mapping, so a day's
 * counts are always the feed's entries of that day. Mode changes have no result and are not counted.
 *
 * <p>Only days XamOps can count honestly are listed. A day before {@code firstRecordedAt} (nothing
 * recorded yet) or before {@code completeSince} (only the resizes that reached a pod were recorded
 * then, so test runs, refusals, put-backs and HPA changes would read as zero) is left out, never
 * zero-filled. Every day from the first listed day to {@code to} is present, and a listed day with
 * nothing on it is a real zero. XamOps cannot tell when on the {@code completeSince} day the build was
 * deployed, so that first day can miss an attempt made earlier on it.
 *
 * @param engine          whether the Workload autoscaler is on in this environment; the summary is
 *                        served either way, like the feed
 * @param timezone        {@code Asia/Kolkata}, the calendar every {@code date} is in
 * @param from            the window's first day, {@code yyyy-MM-dd}: {@code days - 1} days before
 *                        {@code to}
 * @param to              today, {@code yyyy-MM-dd}, on the server's clock
 * @param firstRecordedAt the oldest entry of the selected features on this cluster, at any age; null
 *                        when there is none
 * @param completeSince   when XamOps started recording every kind of attempt on this cluster: its first
 *                        test run, apply that changed no pod, HPA change, or resize stored with its
 *                        proof. Null until there is one, and then no day is listed
 * @param truncated       more rows fell in the window than one summary counts (10,000); the day the
 *                        cap cut into and every day before it are left out
 * @param days            oldest first
 */
public record ActivitySummaryDto(ActivityPageDto.Engine engine, String timezone, String from, String to,
                                 Instant firstRecordedAt, Instant completeSince, boolean truncated,
                                 List<Day> days) {

    public ActivitySummaryDto {
        days = days == null ? List.of() : List.copyOf(days);
    }

    /**
     * One day's counts, for the selected features together, and per feature.
     *
     * @param date      {@code yyyy-MM-dd} in {@code Asia/Kolkata}
     * @param applied   result {@code APPLIED}
     * @param putBack   result {@code PUT_BACK}: deferred or infeasible, and put back
     * @param refused   result {@code REFUSED}
     * @param testRuns  result {@code TEST_RUN}
     * @param failed    result {@code FAILED}
     * @param byFeature {@code VERTICAL} and {@code HPA}, each present when selected
     */
    public record Day(String date, int applied, int putBack, int refused, int testRuns, int failed,
                      Map<String, Counts> byFeature) {

        public Day {
            // Insertion order kept, so VERTICAL comes before HPA on the wire.
            byFeature = byFeature == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(byFeature));
        }
    }

    public record Counts(int applied, int putBack, int refused, int testRuns, int failed) {
    }
}
