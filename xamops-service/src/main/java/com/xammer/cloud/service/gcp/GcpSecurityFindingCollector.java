package com.xammer.cloud.service.gcp;

import com.xammer.cloud.dto.gcp.GcpSecurityFinding;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Accumulates findings as they stream in, keeping memory bounded.
 *
 * <p>
 * Up to {@code maxFindings} findings are kept in arrival order. Past that the collector switches to
 * per-severity buckets (CRITICAL, HIGH, MEDIUM, LOW, other), each capped, and {@link #retained()}
 * returns the most severe {@code maxFindings}. Exact totals are counted on every call to
 * {@link #add}, whether or not the finding is kept.
 */
final class GcpSecurityFindingCollector {

    private static final String[] SEVERITIES = {"CRITICAL", "HIGH", "MEDIUM", "LOW"};
    static final String OTHER_GROUP = "__OTHER__";
    private static final int MAX_GROUP_KEYS = 5000;

    private final int maxFindings;
    private List<GcpSecurityFinding> ordered = new ArrayList<>();
    private List<List<GcpSecurityFinding>> buckets; // null until the cap is exceeded
    private long total;
    private final Map<String, Long> severityTotals = new HashMap<>();
    private final Map<String, Long> groupCounts = new HashMap<>();

    GcpSecurityFindingCollector(int maxFindings) {
        this.maxFindings = Math.max(1, maxFindings);
    }

    void add(GcpSecurityFinding finding) {
        total++;
        String severity = finding.getSeverity() == null ? "UNKNOWN" : finding.getSeverity().toUpperCase();
        severityTotals.merge(severity, 1L, Long::sum);
        if ("CRITICAL".equals(severity) || "HIGH".equals(severity)) {
            String key = GcpSecurityGrouping.groupKey(finding);
            if (!groupCounts.containsKey(key) && groupCounts.size() >= MAX_GROUP_KEYS) {
                key = OTHER_GROUP;
            }
            groupCounts.merge(key, 1L, Long::sum);
        }

        if (buckets == null) {
            if (ordered.size() < maxFindings) {
                ordered.add(finding);
                return;
            }
            // First overflow: from here on keep only the most severe findings.
            buckets = new ArrayList<>(SEVERITIES.length + 1);
            for (int i = 0; i <= SEVERITIES.length; i++) {
                buckets.add(new ArrayList<>());
            }
            for (GcpSecurityFinding f : ordered) {
                addToBucket(f);
            }
            ordered = null;
        }
        addToBucket(finding);
    }

    private void addToBucket(GcpSecurityFinding finding) {
        List<GcpSecurityFinding> bucket = buckets.get(rank(finding.getSeverity()));
        if (bucket.size() < maxFindings) {
            bucket.add(finding);
        }
    }

    private static int rank(String severity) {
        if (severity != null) {
            for (int i = 0; i < SEVERITIES.length; i++) {
                if (SEVERITIES[i].equalsIgnoreCase(severity)) {
                    return i;
                }
            }
        }
        return SEVERITIES.length;
    }

    long total() {
        return total;
    }

    boolean truncated() {
        return total > maxFindings;
    }

    /** The findings to keep: arrival order while under the cap, most severe first once over it. */
    List<GcpSecurityFinding> retained() {
        if (buckets == null) {
            return ordered;
        }
        List<GcpSecurityFinding> out = new ArrayList<>(maxFindings);
        for (List<GcpSecurityFinding> bucket : buckets) {
            for (GcpSecurityFinding f : bucket) {
                if (out.size() >= maxFindings) {
                    return out;
                }
                out.add(f);
            }
        }
        return out;
    }

    GcpSecurityDigest digest(int retainedCount) {
        return new GcpSecurityDigest(total, Map.copyOf(severityTotals), Map.copyOf(groupCounts),
                total > retainedCount, retainedCount);
    }

    /**
     * Digest that uses exact severity totals obtained elsewhere (SCC GroupFindings) when given.
     * {@code groupCounts} still come from the findings actually seen, so they are lower bounds
     * when the fetch was cut short.
     */
    GcpSecurityDigest digest(int retainedCount, boolean truncated, boolean approximate,
                             Map<String, Long> exactSeverityTotals) {
        if (exactSeverityTotals == null) {
            return new GcpSecurityDigest(total, Map.copyOf(severityTotals), Map.copyOf(groupCounts),
                    truncated, retainedCount, approximate);
        }
        long exactTotal = exactSeverityTotals.values().stream().mapToLong(Long::longValue).sum();
        return new GcpSecurityDigest(exactTotal, Map.copyOf(exactSeverityTotals), Map.copyOf(groupCounts),
                truncated, retainedCount, approximate);
    }

    Map<String, Long> severityTotalsSnapshot() {
        return new HashMap<>(severityTotals);
    }

    Map<String, Long> severityTotals() {
        return severityTotals;
    }
}
