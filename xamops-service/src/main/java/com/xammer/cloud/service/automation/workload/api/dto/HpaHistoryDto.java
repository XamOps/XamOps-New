package com.xammer.cloud.service.automation.workload.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * The response body of {@code GET /api/eks/automation/hpa/{cluster}/{namespace}/{name}/history}: one
 * HPA's replicas, bounds and CPU against its target over a window, from kube-state-metrics.
 *
 * <p>{@code series} are at the window's step. {@code atMax} and {@code changes} come from 60-second
 * samples over the same span (at most 10,081 points for 7 days), so a dip shorter than a chart step
 * still shows. A source failure is a {@code 200} with {@code unavailable} set and nothing invented.
 *
 * @param schemaVersion    1
 * @param namespace        the HPA's namespace
 * @param name             the HPA's name
 * @param window           {@code 24h | 72h | 168h}
 * @param stepSeconds      the step of {@code series}
 * @param start            the first point's time
 * @param end              the last point's time
 * @param source           where the numbers come from
 * @param series           {@code currentReplicas, minReplicas, maxReplicas, cpuUtilization,
 *                         cpuTarget}, in that order; empty when unavailable
 * @param atMax            time spent at {@code maxReplicas}; null when unavailable
 * @param changes          every change of the running replica count, oldest first, at most the
 *                         newest 200
 * @param changesTruncated whether older changes were left out to stay within 200
 * @param firstSampleAt    the first time any series has a sample; null when none has
 * @param unavailable      why there is no data; null when there is
 */
public record HpaHistoryDto(int schemaVersion, String namespace, String name, String window, int stepSeconds,
                            Instant start, Instant end, UsageSeriesDto.Source source, List<Series> series,
                            AtMax atMax, List<Change> changes, boolean changesTruncated,
                            Instant firstSampleAt, UsageSeriesDto.Unavailable unavailable) {

    public HpaHistoryDto {
        series = series == null ? List.of() : List.copyOf(series);
        changes = changes == null ? List.of() : List.copyOf(changes);
    }

    /**
     * @param key    {@code currentReplicas | minReplicas | maxReplicas | cpuUtilization | cpuTarget}
     * @param label  the legend word (glossary §15)
     * @param unit   {@code replicas | percent}
     * @param points one per step; {@code null} where nothing was measured
     * @param query  the PromQL that produced them
     */
    public record Series(String key, String label, String unit, List<UsageSeriesDto.Point> points,
                         String query) {

        public Series {
            points = points == null ? List.of() : List.copyOf(points);
        }
    }

    /**
     * From {@code current_replicas >= bool max_replicas} at 60 s: one sample is one minute.
     *
     * @param share           {@code minutesAtMax / minutesMeasured}; null when nothing was measured
     * @param minutesAtMax    minutes at the maximum
     * @param minutesMeasured minutes with a sample
     * @param runs            unbroken spells at the maximum, oldest first, at most the newest 1,000;
     *                        {@code to} is the first minute no longer at the maximum (or unmeasured),
     *                        null while the spell is still running at {@code end}
     * @param runsTruncated   whether older spells were left out to stay within 1,000
     * @param since           when the current spell began, if the HPA is at its maximum at
     *                        {@code end}; null otherwise
     * @param stepSeconds     60
     * @param query           the PromQL
     */
    public record AtMax(Double share, int minutesAtMax, int minutesMeasured, List<Run> runs,
                        boolean runsTruncated, Instant since, int stepSeconds, String query) {

        public AtMax {
            runs = runs == null ? List.of() : List.copyOf(runs);
        }
    }

    /** One spell at the maximum: {@code from} inclusive, {@code to} exclusive or null while running. */
    public record Run(Instant from, Instant to) {
    }

    /** The running replica count moved from {@code from} to {@code to}, first seen at {@code at}. */
    public record Change(Instant at, int from, int to) {
    }
}
