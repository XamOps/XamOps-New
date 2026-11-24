package com.xammer.cloud.autospotting.ledger.observe;

import com.xammer.cloud.service.autospotting.ledger.observe.EndCauseClassifier;
import com.xammer.cloud.service.autospotting.ledger.observe.IntervalTracker;
import com.xammer.cloud.service.autospotting.ledger.observe.IntervalTracker.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** plan-phase-17.md 17.2 / design-ledger.md §6.4, §3.6 as pure rules. */
class IntervalTrackerTest {
    private static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");

    private static Present p(String id) {
        return new Present(id, "m5.xlarge", "us-east-1a", "LINUX", "SPOT", new BigDecimal("1.000000"), NOW.minusSeconds(600));
    }

    @Test
    void newInstanceOpensAnIntervalAtItsLaunchTime() {
        var acts = IntervalTracker.plan(NOW, List.of(), Set.of(), Map.of("i-1", p("i-1")), Map.of());
        assertThat(acts).singleElement().isInstanceOfSatisfying(OpenNew.class, o -> {
            assertThat(o.startAt()).isEqualTo(NOW.minusSeconds(600));
            assertThat(o.startSource()).isEqualTo("EC2_LAUNCH_TIME");
        });
    }

    @Test
    void launchedAndReplacedYieldsOneClosedIntervalWithEndCauseReplaced() {
        UUID id = UUID.randomUUID();
        var open = new Open(id, "i-1", NOW.minusSeconds(600), NOW.minusSeconds(300), null);
        var cause = EndCauseClassifier.classify(null, "At 2026-10-05T09:59:00Z an instance was taken out of service in response to an EC2 instance rebalance recommendation.");
        var ending = new Ending("i-1", NOW.minusSeconds(30), cause, null);
        var acts = IntervalTracker.plan(NOW, List.of(open), Set.of("i-1"), Map.of(), Map.of("i-1", ending));
        assertThat(acts).singleElement().isInstanceOfSatisfying(Close.class, c -> {
            assertThat(c.id()).isEqualTo(id);
            assertThat(c.endAt()).isEqualTo(NOW.minusSeconds(30));
            assertThat(c.endSource()).isEqualTo("ASG_ACTIVITY");
            assertThat(c.cause().cause()).isEqualTo("REPLACED");
        });
    }

    @Test
    void missedSweepLeavesFirstAbsentAtAsTheUpperBound() {
        UUID id = UUID.randomUUID();
        var open = new Open(id, "i-1", NOW.minusSeconds(600), NOW.minusSeconds(300), null);
        var acts = IntervalTracker.plan(NOW, List.of(open), Set.of("i-1"), Map.of(), Map.of());
        assertThat(acts).singleElement().isInstanceOfSatisfying(Absent.class, a -> assertThat(a.firstAbsentAt()).isEqualTo(NOW));
        // A later sweep inside the grace period changes nothing: the bound stays the first absence.
        var absent = new Open(id, "i-1", NOW.minusSeconds(600), NOW.minusSeconds(300), NOW);
        assertThat(IntervalTracker.plan(NOW.plusSeconds(300), List.of(absent), Set.of("i-1"), Map.of(), Map.of())).isEmpty();
    }

    @Test
    void absentPastTheGraceClosesWithAnUnknownEndAndNoEndAt() {
        UUID id = UUID.randomUUID();
        var absent = new Open(id, "i-1", NOW.minusSeconds(7200), NOW.minusSeconds(5000), NOW.minusSeconds(4000));
        var acts = IntervalTracker.plan(NOW, List.of(absent), Set.of("i-1"), Map.of(), Map.of());
        assertThat(acts).singleElement().isInstanceOfSatisfying(Close.class, c -> {
            assertThat(c.endAt()).isNull();
            assertThat(c.cause().cause()).isEqualTo("UNKNOWN");
        });
    }

    @Test
    void reappearingInstanceBumpsLastSeen() {
        UUID id = UUID.randomUUID();
        var open = new Open(id, "i-1", NOW.minusSeconds(600), NOW.minusSeconds(300), NOW.minusSeconds(100));
        var acts = IntervalTracker.plan(NOW, List.of(open), Set.of("i-1"), Map.of("i-1", p("i-1")), Map.of());
        assertThat(acts).singleElement().isInstanceOf(Seen.class);
    }

    @Test
    void spotReclaimIsReadFromTheEc2StateReason() {
        assertThat(EndCauseClassifier.classify("Server.SpotInstanceTermination", null).cause()).isEqualTo("PROVIDER_INTERRUPTED");
        assertThat(EndCauseClassifier.classify(null, null).cause()).isEqualTo("UNKNOWN");
    }
}
