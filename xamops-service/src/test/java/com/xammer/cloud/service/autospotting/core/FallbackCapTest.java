package com.xammer.cloud.service.autospotting.core;

import com.xammer.cloud.domain.autospotting.Provider;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The daily cap and the exit backoff (design-aws.md §6.5, DC-07, AC-HLT-04). Provider neutral: the same rule runs
 * for AWS and for the GCP rules of gcp §7.2.
 */
class FallbackCapTest {
    private static final Instant DAY1 = Instant.parse("2026-11-20T00:00:00Z");

    @Test
    void enterIsAlwaysAllowedAndAnExitNeedsRoomForOneMoreEnter() {
        FallbackCap cap = new FallbackCap(5);
        for (int n = 0; n <= 20; n++) {
            assertThat(cap.enterAllowed(n)).isTrue();
        }
        assertThat(cap.exitAllowed(3)).isTrue();   // 3 + 2 <= 5
        assertThat(cap.exitAllowed(4)).isFalse();
        assertThat(cap.exitAllowed(5)).isFalse();
        assertThat(cap.heldByCap(4)).isTrue();
    }

    @Test
    void theCapCannotBeSetBelowOneEnterAndOneExit() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> new FallbackCap(1));
        assertThat(FallbackProperties.withCap(1).maxTransitionsPerDay()).isEqualTo(2);
    }

    @Test
    void transitionsAreCountedPerUtcDay() {
        List<Instant> starts = List.of(DAY1.minusSeconds(1), DAY1, DAY1.plusSeconds(3600), DAY1.plus(Duration.ofHours(24)));
        assertThat(FallbackCap.transitionsToday(starts, DAY1.plusSeconds(60))).isEqualTo(2);
        assertThat(FallbackCap.nextDayStart(DAY1.plusSeconds(60))).isEqualTo(DAY1.plus(Duration.ofHours(24)));
    }

    /** A shortfall every other hour for two days: at most {@code cap} transitions a day, every needed enter runs. */
    @ParameterizedTest
    @EnumSource(Provider.class)
    void overASimulatedUtcDayAtMostCapTransitionsAndEveryNeededEnterRuns(Provider provider) {
        int capValue = 5;
        FallbackCap cap = new FallbackCap(capValue);
        List<Instant> starts = new ArrayList<>();
        boolean inFallback = false;
        int neededEnters = 0;
        int ranEnters = 0;
        boolean heldByCapSeen = false;
        Instant firstExitOfDay2 = null;
        for (int hour = 0; hour < 48; hour++) {
            Instant now = DAY1.plus(Duration.ofHours(hour));
            boolean shortfall = hour % 2 == 0;
            int today = FallbackCap.transitionsToday(starts, now);
            if (shortfall && !inFallback) {
                neededEnters++;
                assertThat(cap.enterAllowed(today)).isTrue();
                starts.add(now);
                inFallback = true;
                ranEnters++;
            } else if (!shortfall && inFallback) {
                if (cap.exitAllowed(today)) {
                    starts.add(now);
                    inFallback = false;
                    if (hour >= 24 && firstExitOfDay2 == null) {
                        firstExitOfDay2 = now;
                    }
                } else {
                    heldByCapSeen = true; // held in FALLBACK by the cap: never DEGRADED, no enter needed
                }
            }
        }
        for (int day = 0; day < 2; day++) {
            Instant d = DAY1.plus(Duration.ofHours(24L * day + 12));
            assertThat(FallbackCap.transitionsToday(starts, d)).as(provider + " day " + day).isLessThanOrEqualTo(capValue);
        }
        assertThat(ranEnters).isEqualTo(neededEnters);
        assertThat(heldByCapSeen).as("the cap held the group in FALLBACK").isTrue();
        assertThat(firstExitOfDay2).as("it exits after 00:00 UTC").isNotNull();
        assertThat(firstExitOfDay2).isEqualTo(DAY1.plus(Duration.ofHours(25)));
    }

    @Test
    void theExitBackoffIsDwellThenOneTwoFourThenEightHours() {
        FallbackBackoff b = FallbackBackoff.of(FallbackProperties.defaults());
        Instant since = DAY1;
        assertThat(b.nextAttemptAt(since, null, 0, false)).isEqualTo(since.plus(Duration.ofMinutes(60)));
        Instant failedAt = since.plus(Duration.ofHours(2));
        assertThat(b.nextAttemptAt(since, failedAt, 1, false)).isEqualTo(failedAt.plus(Duration.ofHours(1)));
        assertThat(b.nextAttemptAt(since, failedAt, 2, false)).isEqualTo(failedAt.plus(Duration.ofHours(2)));
        assertThat(b.nextAttemptAt(since, failedAt, 3, false)).isEqualTo(failedAt.plus(Duration.ofHours(4)));
        assertThat(b.nextAttemptAt(since, failedAt, 4, false)).isEqualTo(failedAt.plus(Duration.ofHours(8)));
        assertThat(b.nextAttemptAt(since, failedAt, 9, false)).isEqualTo(failedAt.plus(Duration.ofHours(8)));
    }

    @Test
    void aReentryWithinThirtyMinutesOfAnExitDoublesTheInterval() {
        FallbackBackoff b = FallbackBackoff.of(FallbackProperties.defaults());
        assertThat(b.nextAttemptAt(DAY1, null, 0, true)).isEqualTo(DAY1.plus(Duration.ofMinutes(120)));
    }
}
