package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;

/**
 * One firing in an alert backtest. {@code from}: first evaluation that breached; {@code firedAt}: when it would have
 * fired (after "for"); {@code to}: when it would have recovered, or the end of the range when {@code ongoing}.
 * {@code peak} is the worst value (highest for &gt;, lowest for &lt;), null when the firing was only missing data.
 * {@code notified} is false for a repeat inside the dedupe window and for a quick re-open (flap guard), which the live
 * evaluator does not notify either.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpFiring(Instant from, Instant firedAt, Instant to, Map<String, String> group, Double peak, boolean ongoing, boolean notified) {

    public XpFiring(Instant from, Instant to, Map<String, String> group, Double peak) {
        this(from, from, to, group, peak, false, true);
    }
}
