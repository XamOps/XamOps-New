package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Container 360: one container (by name on one host) across the window, through every redeploy.
 *
 * <p>Series keys (epoch ms, value): {@code cpu} (percent of one CPU), {@code memory} and {@code memory_limit} (bytes),
 * {@code throttled_pct} (share of CPU periods throttled), {@code network_rx} / {@code network_tx} and
 * {@code disk_read} / {@code disk_write} (bytes per second). A key is absent when the container did not report it.
 *
 * @param versions every container id this name had, oldest first, with its image and when it was seen
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpContainerSummary(String instanceId, String name, String containerId, String image, Instant firstSeen,
                                 Instant lastSeen, Double memLimitBytes, Long restarts, List<Version> versions,
                                 Map<String, List<double[]>> series, List<XpHostLimits.Finding> findings,
                                 List<XpChange> changes, List<XpLogRow> recentErrors) {

    public record Version(String containerId, String image, Instant firstSeen, Instant lastSeen) {}
}
