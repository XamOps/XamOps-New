package com.xammer.cloud.service.autospotting.ledger.calc;

import java.time.Instant;

/** Instants as whole epoch seconds: the ledger's integer durations {@code Δ_k} (design-ledger.md §3.1). */
final class Instants {
    static final long DAY = 86_400L;
    /** Stands for "still running": later than any accrual day. */
    static final long OPEN = Long.MAX_VALUE;

    private Instants() {}

    /** Epoch second; a fraction of a second is truncated (the ledger measures whole seconds). */
    static long sec(Instant i) {
        return i.getEpochSecond();
    }
}
