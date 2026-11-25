package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * Result of replaying a rule over a past range with the live evaluator's queries and state machine.
 * {@code wouldNotify}: "firing" notifications that would have been sent (after dedupe); {@code resolved}: recovery
 * notifications; {@code suppressedRepeats}: firings inside the dedupe window; {@code quietReopens}: re-breaches within
 * five minutes of recovering. {@code series}: the value the rule compared with its threshold, minute by minute, for
 * the groups most worth seeing ({@code [epochMs, value]}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpAlertPreview(List<XpFiring> firings, int evaluatedPoints, int wouldNotify,
                             Instant from, Instant to, String op, Double threshold, Integer groups, Integer resolved,
                             Integer suppressedRepeats, Integer quietReopens, List<Series> series, List<String> notes) {

    public XpAlertPreview(List<XpFiring> firings, int evaluatedPoints, int wouldNotify) {
        this(firings, evaluatedPoints, wouldNotify, null, null, null, null, null, null, null, null, null, null);
    }

    public record Series(String group, List<double[]> points) {}
}
