package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Throttle Radar for one host: the limits that were actually hit in the window (findings, each with the measured
 * numbers behind it) and, for every signal the radar knows, whether it could be measured on this host at all.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpHostLimits(String instanceId, Instant from, Instant to, List<Finding> findings, List<Signal> signals) {

    /**
     * @param severity CRITICAL, WARNING or INFO
     * @param source   who imposed the limit: AWS (network allowances, EBS caps, Spot), KERNEL (pressure, conntrack
     *                 table, CPU steal) or CONTAINER (a cgroup CPU limit)
     * @param evidence the measured values the finding is based on, by name (counts, fractions 0..1, seconds)
     */
    public record Finding(String severity, String code, String source, String title, String detail, String fix,
                          Map<String, Double> evidence) {}

    /** @param state OK (measured, no limit hit), LIMITED (a finding exists), NOT_MEASURED (with the reason) */
    public record Signal(String code, String label, String state, String note) {}
}
