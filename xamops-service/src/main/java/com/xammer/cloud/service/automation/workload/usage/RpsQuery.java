package com.xammer.cloud.service.automation.workload.usage;

import com.xammer.cloud.service.automation.workload.PodNameRule;
import com.xammer.cloud.service.automation.workload.WorkloadRef;

import java.util.List;

/**
 * What to measure for the RPS-based replica sizing (F3): the single-metric sibling of
 * {@link UsageQuery}.
 *
 * <p>It carries exactly the inputs the seven existing per-container queries already carry — so the
 * selector a provider builds from it is scoped identically (namespace + owner-resolved pods +
 * containers, bounded regex) — plus the one thing those queries do not need: the configured
 * request-rate metric expression. An {@link UsageHistoryProvider#observedRps} implementation renders
 * {@link PromQl#requestRate} from this and reduces the result to one scalar.
 *
 * @param ref            the workload being sized ({@code workload.ref()}); namespace mandatory
 * @param containerNames the containers to measure ({@code workload.containerNames()}); mandatory,
 *                       non-empty
 * @param podNames       the owner-resolved pod names ({@code workload.pods().names()}); mandatory,
 *                       non-empty — selected on instead of a name-prefix regex
 * @param podNameRules   the pod-name shapes this workload's controllers produce
 *                       ({@code workload.pods().nameRules()}); may be empty
 * @param windowHours    the policy window, identical to the CPU/mem queries, so RPS is measured over
 *                       the same horizon as the sizing inputs
 * @param offsetSeconds  the young-container exclusion offset
 *                       ({@link VerticalMetricsProperties#ignoreContainerStartSeconds()})
 * @param metricExpr     the configured PromQL request-rate expression
 *                       ({@code RpsSizingProperties.metric()}); {@code null}/blank means no RPS
 *                       source
 */
public record RpsQuery(WorkloadRef ref, List<String> containerNames, List<String> podNames,
                       List<PodNameRule> podNameRules, int windowHours, int offsetSeconds,
                       String metricExpr) {

    public RpsQuery {
        if (ref == null) {
            throw new IllegalArgumentException("ref is mandatory");
        }
        if (containerNames == null || containerNames.isEmpty()) {
            throw new IllegalArgumentException("containerNames is mandatory: usage is per container");
        }
        containerNames = List.copyOf(containerNames);
        if (podNames == null || podNames.isEmpty()) {
            throw new IllegalArgumentException(
                    "podNames is mandatory: a query that does not name its pods is how"
                            + " pod=~\"<name>-.*\" selects another workload's pods");
        }
        podNames = List.copyOf(podNames);
        podNameRules = podNameRules == null ? List.of() : List.copyOf(podNameRules);
        if (windowHours < 1) {
            throw new IllegalArgumentException("windowHours must be positive, got " + windowHours);
        }
        if (offsetSeconds < 0) {
            throw new IllegalArgumentException("offsetSeconds cannot be negative, got " + offsetSeconds);
        }
    }

    /** Whether a request-rate metric was configured at all; blank means there is no RPS source. */
    public boolean hasMetric() {
        return metricExpr != null && !metricExpr.isBlank();
    }
}
