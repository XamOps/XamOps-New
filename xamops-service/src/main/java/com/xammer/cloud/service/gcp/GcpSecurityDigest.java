package com.xammer.cloud.service.gcp;

import java.util.Map;

/**
 * Exact totals for one project's security findings, computed while the findings are streamed.
 *
 * <p>
 * The finding list itself is capped ({@code xamops.gcp.security.max-findings}), so a project with
 * hundreds of thousands of findings can no longer be counted from the list. This small summary keeps
 * the true numbers so alert rows and the score do not silently drop to the cap.
 *
 * @param total                  every finding seen, retained or not
 * @param severityTotals         upper-cased severity to count
 * @param criticalHighGroupCounts {@link GcpSecurityGrouping#groupKey} to count, CRITICAL and HIGH only
 * @param truncated              true when the retained list is shorter than {@code total}
 * @param retained               number of findings kept in the list
 * @param approximate            true when the SCC fetch stopped at its page/finding cap and exact totals
 *                               (SCC GroupFindings) were unavailable, so every number is a lower bound
 */
public record GcpSecurityDigest(
        long total,
        Map<String, Long> severityTotals,
        Map<String, Long> criticalHighGroupCounts,
        boolean truncated,
        int retained,
        boolean approximate) {

    /** Exact-or-complete digest ({@code approximate=false}); also reads back older stored digests. */
    public GcpSecurityDigest(long total, Map<String, Long> severityTotals,
                             Map<String, Long> criticalHighGroupCounts, boolean truncated, int retained) {
        this(total, severityTotals, criticalHighGroupCounts, truncated, retained, false);
    }
}
