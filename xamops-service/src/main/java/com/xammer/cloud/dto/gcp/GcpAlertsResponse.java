package com.xammer.cloud.dto.gcp;

import com.xammer.cloud.dto.AlertDto;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The alerts payload plus the metadata needed to describe how much to trust it.
 *
 * <p>
 * The endpoint used to return a bare {@code List<AlertDto>}, which cannot express the difference
 * between the two situations a user most needs told apart: "we checked and found nothing" and "we
 * could not check". Both arrived as {@code []} and the page rendered a green "All Clear!" for each.
 * On a security surface the second case reading as the first is the dangerous direction to fail in.
 *
 * <p>
 * {@link #generatedAt} exists for the same reason. The page showed a pulsing "Active monitoring"
 * indicator that was hardcoded, so a snapshot rebuilt days ago was presented with the same
 * confidence as one rebuilt a second ago.
 */
@Data
@NoArgsConstructor
public class GcpAlertsResponse {

    private List<AlertDto> alerts = new ArrayList<>();

    /** When the returned data was actually built, not when it was served. */
    private LocalDateTime generatedAt;

    /** True when at least one source could not be checked, so the list may be incomplete. */
    private boolean degraded;

    /** Human-readable reasons, one per source that failed. Empty when {@link #degraded} is false. */
    private List<String> degradedReasons = new ArrayList<>();

    /** True when the data came from a cache rather than a live rebuild on this request. */
    private boolean fromCache;

    /**
     * Total underlying findings represented, which exceeds {@code alerts.size()} whenever rows are
     * grouped. Lets the UI say "12 issues across 2,124 resources" without re-deriving it.
     */
    private int totalFindings;

    public GcpAlertsResponse(List<AlertDto> alerts, LocalDateTime generatedAt, boolean fromCache) {
        this.alerts = alerts == null ? new ArrayList<>() : alerts;
        this.generatedAt = generatedAt;
        this.fromCache = fromCache;
        this.totalFindings = this.alerts.stream().mapToInt(AlertDto::effectiveCount).sum();
    }

    /** A response for the case where the rebuild itself threw and produced nothing usable. */
    public static GcpAlertsResponse failed(String reason) {
        GcpAlertsResponse response = new GcpAlertsResponse(Collections.emptyList(), LocalDateTime.now(), false);
        response.setDegraded(true);
        response.setDegradedReasons(new ArrayList<>(List.of(reason)));
        return response;
    }

    public void markDegraded(List<String> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return;
        }
        this.degraded = true;
        this.degradedReasons = new ArrayList<>(reasons);
    }
}
