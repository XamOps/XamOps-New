package com.xammer.cloud.service.spot;

import java.time.Instant;

/**
 * Lazily supplies a row's workloads (design §B4.2). Invoked by the writer only when the merged row's
 * {@code affected_workloads} is still unknown and the row is inside the recovery window, with the
 * merged row's {@code noticed_at}, so capture rule 2 compares against the earliest notice.
 */
@FunctionalInterface
public interface WorkloadProvider {

    /** @return the capture, or null when the snapshot can't say (unknown) */
    WorkloadCapture capture(Instant noticedAt);
}
