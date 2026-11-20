package com.xammer.cloud.service.autospotting.core;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;

/**
 * The daily fallback cap, one rule for both clouds (design-aws.md §6.5, DC-07, AC-HLT-04). FALLBACK_ENTER and
 * FALLBACK_EXIT together count toward the cap per group per UTC day, each once, when it starts. An enter is
 * SAFETY and always allowed. An exit is allowed only while it leaves room for one more enter that day
 * ({@code transitionsToday + 2 <= cap}), so a day never exceeds the cap and a group is never stranded short of
 * capacity because of it. The cap never makes a group DEGRADED: a group held in FALLBACK by the cap simply
 * exits after 00:00 UTC. Pure: time is a parameter.
 */
public final class FallbackCap {
    private final int cap;

    public FallbackCap(int cap) {
        if (cap < 2) {
            throw new IllegalArgumentException("the cap must allow one enter and one exit");
        }
        this.cap = cap;
    }

    public int cap() {
        return cap;
    }

    /** Enter is SAFETY: always allowed, whatever the count. */
    public boolean enterAllowed(int transitionsToday) {
        return true;
    }

    /** An exit must leave room for one more enter the same day. */
    public boolean exitAllowed(int transitionsToday) {
        return transitionsToday + 2 <= cap;
    }

    /** The first instant of the UTC day containing {@code now}. */
    public static Instant dayStart(Instant now) {
        return now.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** 00:00 UTC of the next day: when a group held by the cap may exit. */
    public static Instant nextDayStart(Instant now) {
        return dayStart(now).plusSeconds(24 * 3600L);
    }

    /** The transitions (op start times) that fall on {@code now}'s UTC day. */
    public static int transitionsToday(Collection<Instant> starts, Instant now) {
        Instant from = dayStart(now);
        Instant to = from.plusSeconds(24 * 3600L);
        int n = 0;
        for (Instant s : starts) {
            if (s != null && !s.isBefore(from) && s.isBefore(to)) {
                n++;
            }
        }
        return n;
    }

    /** True when the cap, and nothing else, is what keeps an exit from starting now. */
    public boolean heldByCap(int transitionsToday) {
        return !exitAllowed(transitionsToday);
    }
}
