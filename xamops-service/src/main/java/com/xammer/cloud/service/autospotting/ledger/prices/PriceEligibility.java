package com.xammer.cloud.service.autospotting.ledger.prices;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Which price snapshot may price a window (design-ledger.md §4.4, AC-LED-18). A snapshot may price
 * {@code [a, b)} only if
 * <ol>
 *   <li>{@code effective_from <= a} and ({@code effective_to} is null or {@code effective_to >= b}), or the
 *       caller splits the window at {@code effective_to}; and</li>
 *   <li>{@code source_effective_time_stated} is true, or {@code fetched_at <= a}. A price with no stated
 *       effective time is valid only from the moment it was observed, so it can never price the past.</li>
 * </ol>
 * {@link #usableFrom} folds rule 2 into the start of the validity window, so a caller that hands the
 * calculator {@link #clamp clamped} snapshots gets {@code MISSING_SPOT_PRICE} or {@code MISSING_OD_PRICE}
 * for any segment no eligible snapshot covers, and never a price from the future.
 */
public final class PriceEligibility {

    private PriceEligibility() {
    }

    /** The fields of a stored {@code spot_price_snapshot} row that decide eligibility. */
    public record Snapshot(String id, BigDecimal rate, Instant effectiveFrom, Instant effectiveTo,
                           boolean sourceEffectiveTimeStated, Instant fetchedAt) {
    }

    /** The first instant this snapshot may price: its stated start, or for an unstated one the later of start and fetch. */
    public static Instant usableFrom(Snapshot s) {
        if (s.sourceEffectiveTimeStated() || s.fetchedAt() == null) {
            return s.effectiveFrom();
        }
        return s.fetchedAt().isAfter(s.effectiveFrom()) ? s.fetchedAt() : s.effectiveFrom();
    }

    /** Whether the snapshot may price the whole window {@code [a, b)} (rules 1 and 2). */
    public static boolean mayPrice(Snapshot s, Instant a, Instant b) {
        if (usableFrom(s).isAfter(a)) {
            return false;
        }
        return s.effectiveTo() == null || !s.effectiveTo().isBefore(b);
    }

    /** The snapshot with its start moved to {@link #usableFrom}; empty when nothing of it is left. */
    public static Optional<Snapshot> clamp(Snapshot s) {
        Instant from = usableFrom(s);
        if (s.effectiveTo() != null && !s.effectiveTo().isAfter(from)) {
            return Optional.empty();
        }
        return Optional.of(new Snapshot(s.id(), s.rate(), from, s.effectiveTo(), s.sourceEffectiveTimeStated(),
                s.fetchedAt()));
    }

    /**
     * The snapshots of one price series as the calculator may see them for a day: clamped by rule 2 and
     * ordered by start. A snapshot with no stated end is closed at the next snapshot's start, which is how
     * AWS Spot history is read (a row holds until the next one).
     */
    public static List<Snapshot> forSeries(List<Snapshot> series) {
        List<Snapshot> usable = new ArrayList<>();
        for (Snapshot s : series) {
            clamp(s).ifPresent(usable::add);
        }
        usable.sort(Comparator.comparing(Snapshot::effectiveFrom).thenComparing(Snapshot::id));
        List<Snapshot> out = new ArrayList<>(usable.size());
        for (int i = 0; i < usable.size(); i++) {
            Snapshot s = usable.get(i);
            Instant to = s.effectiveTo();
            if (to == null && i + 1 < usable.size()) {
                to = usable.get(i + 1).effectiveFrom();
            }
            out.add(new Snapshot(s.id(), s.rate(), s.effectiveFrom(), to, s.sourceEffectiveTimeStated(),
                    s.fetchedAt()));
        }
        return out;
    }
}
