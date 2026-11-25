package com.xammer.cloud.xampulse.service.stream;

import java.time.Instant;
import java.util.List;

/**
 * What a live-tail ticket is bound to. Captured at issue time because WebSocket threads have no security context.
 * {@code actorUserId} is the real admin when the ticket was issued while impersonating {@code userId}, else null.
 */
public record TailTicketData(long userId, List<Long> accountIds, String query, Instant issuedAt, Long actorUserId) {
    public TailTicketData(long userId, List<Long> accountIds, String query, Instant issuedAt) {
        this(userId, accountIds, query, issuedAt, null);
    }
}
