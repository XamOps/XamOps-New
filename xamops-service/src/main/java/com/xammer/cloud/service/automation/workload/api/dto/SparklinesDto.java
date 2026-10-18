package com.xammer.cloud.service.automation.workload.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * The response body of {@code GET /api/eks/automation/usage/{cluster}/sparklines}: one small series
 * pair per workload, for the Workloads table.
 *
 * <p>Two range queries per cluster, then every series is sorted into a workload by that workload's
 * own engine selector (owner-resolved pod names plus its {@code PodNameRule}s, and its template's
 * containers), so a row's sparkline reads exactly the pods its suggestion was computed from. Per
 * step, a workload's value is its busiest pod's: the pod's selected containers summed, the maximum
 * across pods. A step no pod answered for is {@code null}.
 *
 * @param schemaVersion 1
 * @param resource      {@code cpu | memory}
 * @param unit          {@code cores | bytes}
 * @param window        {@code 72h}, the only window
 * @param stepSeconds   3600
 * @param start         the first point's time
 * @param end           the last point's time
 * @param source        where the numbers come from
 * @param queries       the two PromQL strings; null when nothing was queried
 * @param workloads     every workload the engine analyses, sorted by namespace, kind and name;
 *                      empty when unavailable
 * @param unavailable   why there is no data; null when there is
 */
public record SparklinesDto(int schemaVersion, String resource, String unit, String window, int stepSeconds,
                            Instant start, Instant end, UsageSeriesDto.Source source, Queries queries,
                            List<WorkloadSparkline> workloads, UsageSeriesDto.Unavailable unavailable) {

    public SparklinesDto {
        workloads = workloads == null ? List.of() : List.copyOf(workloads);
    }

    /**
     * @param used    the PromQL behind every {@code used} series
     * @param request the PromQL behind every {@code request} series
     */
    public record Queries(String used, String request) {
    }

    /**
     * @param ref     the workload
     * @param used    its busiest pod's use per step; every value null when it has no running pod
     * @param request its busiest pod's declared request per step
     */
    public record WorkloadSparkline(UsageSeriesDto.Ref ref, List<UsageSeriesDto.Point> used,
                                    List<UsageSeriesDto.Point> request) {

        public WorkloadSparkline {
            used = used == null ? List.of() : List.copyOf(used);
            request = request == null ? List.of() : List.copyOf(request);
        }
    }
}
