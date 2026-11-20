package com.xammer.cloud.service.autospotting.ledger.observe;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The interval bookkeeping of {@code ledger-observe} (design-ledger.md §2.1, §6.4, §3.6) as a pure
 * function: given the open intervals of a group, the instances the group has now, and what AWS said about
 * instances that ended, it returns the writes. No I/O, no clock, so the rules are testable with fixtures.
 *
 * <ul>
 *   <li>An instance present with no open interval opens one: {@code start_at} is the EC2 launch time when
 *       known, else this sweep ({@code SWEEP_FIRST_SEEN}).</li>
 *   <li>An instance present with an open interval bumps {@code last_seen_at} (the end's lower bound) and
 *       clears a {@code first_absent_at} that turned out to be a missed read.</li>
 *   <li>An open instance that is gone with an authoritative end closes: {@code end_at}, {@code end_source}
 *       and the end cause with its source.</li>
 *   <li>An open instance that is gone with no end sets {@code first_absent_at} to this sweep (the end's
 *       upper bound). If a sweep is missed, the bound is simply later, never earlier. After
 *       {@link #ABSENT_GRACE} with no end found, the interval closes with an unknown end (cause UNKNOWN):
 *       the calculator then prices it between the two bounds (§3.6).</li>
 * </ul>
 */
public final class IntervalTracker {

    /** How long an absent instance waits for its terminating activity before its end is recorded as unknown. */
    public static final Duration ABSENT_GRACE = Duration.ofHours(1);

    private IntervalTracker() {
    }

    /** An instance the group has now. {@code launchTime} may be null. */
    public record Present(String instanceId, String instanceType, String zone, String platform,
                          String purchaseOption, BigDecimal capacityWeight, Instant launchTime) {
    }

    /** An open (not closed) interval from {@code spot_usage_interval}. */
    public record Open(UUID id, String instanceId, Instant startAt, Instant lastSeenAt, Instant firstAbsentAt) {
    }

    /**
     * An authoritative end: from a terminating scaling activity and, for a Spot reclaim, the EC2 state reason.
     * {@code detail} describes the instance (read from EC2 while it is still listed) so an instance that
     * started and ended between two sweeps still gets its interval; it may be null.
     */
    public record Ending(String instanceId, Instant endAt, EndCauseClassifier.Cause cause, Present detail) {
    }

    /** A write the observer must make. */
    public sealed interface Action permits OpenNew, Seen, Absent, Close {
    }

    /** Opens an interval; {@code closedBy} is set when the instance already ended (it is stored closed). */
    public record OpenNew(UUID id, Present instance, Instant startAt, String startSource, Ending closedBy)
            implements Action {
    }

    public record Seen(UUID id, Instant at) implements Action {
    }

    public record Absent(UUID id, Instant firstAbsentAt) implements Action {
    }

    /** {@code endAt} is null for an unknown end; {@code endSource} is {@code ASG_ACTIVITY} or null. */
    public record Close(UUID id, Instant endAt, String endSource, EndCauseClassifier.Cause cause) implements Action {
    }

    /**
     * @param knownInstanceIds every instance id the group already has an interval for (open or closed), so an
     *                         ended instance seen only in an activity is opened once, not on every sweep
     */
    public static List<Action> plan(Instant now, List<Open> open, java.util.Set<String> knownInstanceIds,
                                    Map<String, Present> present, Map<String, Ending> endings) {
        return plan(now, open, knownInstanceIds, present, endings, "ASG_ACTIVITY");
    }

    /** As {@link #plan} for a provider whose authoritative end is {@code endSource} (GCP: {@code GCE_OPERATION}). */
    public static List<Action> plan(Instant now, List<Open> open, java.util.Set<String> knownInstanceIds,
                                    Map<String, Present> present, Map<String, Ending> endings, String endSource) {
        List<Action> out = new ArrayList<>();
        for (Open o : open) {
            Present p = present.get(o.instanceId());
            if (p != null) {
                out.add(new Seen(o.id(), now));
                continue;
            }
            Ending end = endings.get(o.instanceId());
            if (end != null && end.endAt() != null) {
                out.add(new Close(o.id(), end.endAt(), endSource, end.cause()));
            } else if (o.firstAbsentAt() == null) {
                out.add(new Absent(o.id(), now));
            } else if (!now.isBefore(o.firstAbsentAt().plus(ABSENT_GRACE))) {
                out.add(new Close(o.id(), null, null, new EndCauseClassifier.Cause(EndCauseClassifier.UNKNOWN, null)));
            }
        }
        for (Present p : present.values()) {
            if (!knownInstanceIds.contains(p.instanceId())) {
                boolean launched = p.launchTime() != null && !p.launchTime().isAfter(now);
                out.add(new OpenNew(UUID.randomUUID(), p, launched ? p.launchTime() : now,
                        launched ? "EC2_LAUNCH_TIME" : "SWEEP_FIRST_SEEN", null));
            }
        }
        // An instance that started and ended between two sweeps appears only as an ended activity.
        for (Ending e : endings.values()) {
            Present d = e.detail();
            if (d != null && e.endAt() != null && d.launchTime() != null && !knownInstanceIds.contains(e.instanceId())
                    && !present.containsKey(e.instanceId())) {
                out.add(new OpenNew(UUID.randomUUID(), d, d.launchTime(), "EC2_LAUNCH_TIME", e));
            }
        }
        return out;
    }
}
