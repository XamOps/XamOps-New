package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * One-click investigation of a host over a range: every likely explanation checked against measured evidence.
 * {@code status}: SUPPORTED (evidence found), RULED_OUT (measured, not seen), NOT_MEASURABLE (no data to decide).
 * Wording reports what was measured and what came first; it never claims a cause.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpInvestigation(String instanceId, Instant from, Instant to, String summary, List<Hypothesis> hypotheses, XpPeers peers) {

    /**
     * @param confidence HIGH / MEDIUM / LOW for SUPPORTED, null otherwise
     * @param section    the Host 360 section that shows the evidence (aws, changes, cpu, memory, storage, network,
     *                   activity, peers, containers)
     * @param logsQuery  a Logs query that shows the evidence, when there is one
     */
    public record Hypothesis(String code, String title, String status, String confidence, int weight, List<String> evidence,
                             String section, String logsQuery) {}
}
