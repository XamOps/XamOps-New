package com.xammer.cloud.service.autospotting.ledger.calc;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Accumulates an entry's exclusions, one record per reason code with its capacity unit-seconds and the
 * counterfactual it carries (design-ledger.md §6.2, FR-LED-06, AC-LED-16). The informational
 * counterfactual of a state or go-live window is a rate-seconds sum divided by 3600 once, and is null
 * when a price it needs is missing (design-ledger.md §6.3: a missing price outside {@code M(W)} only
 * leaves it null).
 */
final class Exclusions {
    /** One exclusion: reason, capacity unit-seconds (scale 6) and counterfactual cost (scale 10) or null. */
    record Item(String reason, BigDecimal unitSeconds, BigDecimal counterfactualCost) {}

    private static final class Bucket {
        BigDecimal unitSeconds = BigDecimal.ZERO;
        BigDecimal rateSeconds = BigDecimal.ZERO;
        BigDecimal fixedCost = null;
        boolean costUnknown;
        long wallSeconds;
    }

    private final Map<String, Bucket> buckets = new TreeMap<>();

    /** Adds unit-seconds under a reason whose counterfactual is a rate-seconds sum, or null if unknown. */
    void addWindow(String reason, BigDecimal unitSeconds, BigDecimal rateSeconds, long wallSeconds) {
        Bucket b = buckets.computeIfAbsent(reason, r -> new Bucket());
        b.unitSeconds = b.unitSeconds.add(unitSeconds);
        b.wallSeconds += wallSeconds;
        if (rateSeconds == null) {
            b.costUnknown = true;
        } else {
            b.rateSeconds = b.rateSeconds.add(rateSeconds);
        }
    }

    /** Adds unit-seconds that carry zero counterfactual by definition (design-ledger.md §6.2 "Zero"). */
    void addZero(String reason, BigDecimal unitSeconds) {
        addWindow(reason, unitSeconds, BigDecimal.ZERO, 0L);
    }

    /** Sets an exclusion whose cost is a final figure (the commitment deduction). */
    void setFixed(String reason, BigDecimal unitSeconds, BigDecimal cost) {
        Bucket b = buckets.computeIfAbsent(reason, r -> new Bucket());
        b.unitSeconds = b.unitSeconds.add(unitSeconds);
        b.fixedCost = (b.fixedCost == null ? BigDecimal.ZERO : b.fixedCost).add(cost);
    }

    /** Wall-clock seconds recorded under a reason (used to name the entry's reason when {@code M(W)} is empty). */
    long wallSeconds(String reason) {
        Bucket b = buckets.get(reason);
        return b == null ? 0L : b.wallSeconds;
    }

    /** Wall seconds per reason, in reason order. */
    Map<String, Long> wallSecondsByReason() {
        Map<String, Long> out = new TreeMap<>();
        buckets.forEach((k, v) -> {
            if (v.wallSeconds > 0) {
                out.put(k, v.wallSeconds);
            }
        });
        return out;
    }

    List<Item> build() {
        List<Item> out = new ArrayList<>();
        for (Map.Entry<String, Bucket> e : buckets.entrySet()) {
            Bucket b = e.getValue();
            if (b.unitSeconds.signum() == 0 && b.fixedCost == null && b.rateSeconds.signum() == 0) {
                continue;
            }
            BigDecimal cost;
            if (b.fixedCost != null) {
                cost = b.fixedCost.setScale(10, RoundingMode.HALF_UP);
            } else if (b.costUnknown) {
                cost = null;
            } else {
                cost = b.rateSeconds.divide(BigDecimal.valueOf(3600L), 10, RoundingMode.HALF_UP);
            }
            out.add(new Item(e.getKey(), b.unitSeconds.setScale(6, RoundingMode.HALF_UP), cost));
        }
        return out;
    }
}
