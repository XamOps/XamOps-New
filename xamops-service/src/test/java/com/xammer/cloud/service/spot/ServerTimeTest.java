package com.xammer.cloud.service.spot;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC20: with the JVM default zone {@code Asia/Kolkata} (production) and {@code Clock.systemUTC()},
 * an 11-minute-old server-written {@code receivedAt} is stale. Comparing it with
 * {@code LocalDateTime.now(clock)} would make it look 5 h 19 min in the future, i.e. fresh.
 */
class ServerTimeTest {

    private static final Instant NOW = Instant.parse("2026-10-24T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private TimeZone previous;

    @BeforeEach
    void ist() {
        previous = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
    }

    @AfterEach
    void restore() {
        TimeZone.setDefault(previous);
    }

    /** What {@code LocalDateTime.now()} wrote {@code ago} before NOW, in the server zone. */
    private static LocalDateTime serverWritten(Duration ago) {
        return LocalDateTime.ofInstant(NOW.minus(ago), ZoneId.systemDefault());
    }

    @Test
    void anElevenMinuteOldReceivedAtIsStale() {
        assertThat(ServerTime.fresh(serverWritten(Duration.ofMinutes(11)), CLOCK, Duration.ofMinutes(10))).isFalse();
    }

    @Test
    void aNineMinuteOldReceivedAtIsFresh() {
        assertThat(ServerTime.fresh(serverWritten(Duration.ofMinutes(9)), CLOCK, Duration.ofMinutes(10))).isTrue();
    }

    @Test
    void theNaiveComparisonWouldHaveCalledItFresh() {
        LocalDateTime received = serverWritten(Duration.ofMinutes(11));
        assertThat(received.isBefore(LocalDateTime.now(CLOCK).minusMinutes(10)))
                .as("the bug ServerTime exists to prevent: IST wall time vs a UTC clock")
                .isFalse();
    }

    @Test
    void conversionsRoundTripInTheServerZone() {
        LocalDateTime local = LocalDateTime.of(2026, 10, 24, 15, 30);
        Instant instant = ServerTime.serverInstant(local);
        assertThat(instant).isEqualTo(Instant.parse("2026-10-24T10:00:00Z"));
        assertThat(ServerTime.serverLocal(instant)).isEqualTo(local);
        assertThat(instant.toString()).endsWith("Z");
    }

    @Test
    void nullIsUnknownNotFresh() {
        assertThat(ServerTime.serverInstant(null)).isNull();
        assertThat(ServerTime.fresh(null, CLOCK, Duration.ofMinutes(10))).isFalse();
    }
}
