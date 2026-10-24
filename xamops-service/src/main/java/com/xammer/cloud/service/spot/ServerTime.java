package com.xammer.cloud.service.spot;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * The one place a server-written {@link LocalDateTime} becomes an {@link Instant} (design §B1).
 *
 * <p>{@code agent_data_cache.received_at} and {@code agent_events.created_at} are written with
 * {@code LocalDateTime.now()} in the JVM default zone ({@code Asia/Kolkata} in production), while
 * every service clock is {@code Clock.systemUTC()}. A {@code LocalDateTime} is therefore never
 * compared with {@code LocalDateTime.now(clock)}: with a UTC clock that is 5 h 30 min behind IST
 * wall time and every stale report would look fresh. Compare Instants instead.
 */
public final class ServerTime {
    private ServerTime() {}
    /** receivedAt / agent_events.created_at are written with LocalDateTime.now() in the JVM default zone. */
    public static Instant serverInstant(LocalDateTime serverWritten) {
        return serverWritten == null ? null : serverWritten.atZone(ZoneId.systemDefault()).toInstant();
    }
    /** For derived-query parameters against server-written LocalDateTime columns. */
    public static LocalDateTime serverLocal(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }
    public static boolean fresh(LocalDateTime receivedAt, Clock clock, Duration maxAge) {
        Instant at = serverInstant(receivedAt);
        return at != null && !at.isBefore(clock.instant().minus(maxAge));
    }
}
