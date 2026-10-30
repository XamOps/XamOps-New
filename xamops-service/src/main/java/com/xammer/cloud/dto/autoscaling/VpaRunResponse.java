package com.xammer.cloud.dto.autoscaling;

import java.util.List;

/**
 * GET /api/eks/autoscaling/{clusterName}/vpa-runs: the VPA manager's run history (the LIST collector
 * {@code autoscaling-vpa-runs}), newest first. Read-only; nothing here acts on the cluster.
 *
 * <p>
 * Mirrors {@link ConsolidationRunResponse}'s honest-empty-state shape: an empty history is an empty
 * list ({@link #NO_RUNS}), never fabricated; an unreadable stored payload is {@link #UNREADABLE}
 * with an empty list rather than a partial read.
 *
 * @param status {@link #OK}, {@link #NO_RUNS} or {@link #UNREADABLE}
 * @param runs   the stored run history (newest first), or an empty list when none
 */
public record VpaRunResponse(String status, List<VpaRunDto> runs) {

    public static final String OK = "OK";
    public static final String NO_RUNS = "NO_RUNS";
    public static final String UNREADABLE = "UNREADABLE";

    public static VpaRunResponse noRuns() {
        return new VpaRunResponse(NO_RUNS, List.of());
    }

    public static VpaRunResponse unreadable() {
        return new VpaRunResponse(UNREADABLE, List.of());
    }

    public static VpaRunResponse ok(List<VpaRunDto> runs) {
        return new VpaRunResponse(OK, runs);
    }
}
