package com.xammer.cloud.xampulse.service.stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Single-use, 30 second tickets that authorise one WebSocket handshake. 128 bits from {@link SecureRandom}.
 * Consuming deletes the ticket first, so a stolen or replayed ticket works at most once, and a ticket presented by a
 * different logged-in user is burned and refused.
 */
@Service("xpTailTicketService")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class TailTicketService {

    public static final Duration TTL = Duration.ofSeconds(30);
    private static final String PREFIX = "xp:tail:";

    private final TicketStore store;
    private final ObjectMapper mapper;
    private final SecureRandom random = new SecureRandom();

    public TailTicketService(TicketStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.mapper = objectMapper;
    }

    public String issue(TailTicketData data) {
        byte[] raw = new byte[16];
        random.nextBytes(raw);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        try {
            store.put(PREFIX + ticket, mapper.writeValueAsString(new Stored(data.userId(), data.accountIds(), data.query(),
                    data.issuedAt().toEpochMilli(), data.actorUserId())), TTL);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not store tail ticket");
        }
        return ticket;
    }

    /**
     * @param presentedUserId the authenticated user at handshake time if there is one, else null
     */
    public Optional<TailTicketData> consume(String ticket, Long presentedUserId) {
        if (ticket == null || !ticket.matches("^[A-Za-z0-9_-]{22}$")) {
            return Optional.empty();
        }
        Optional<String> json = store.getAndDelete(PREFIX + ticket);
        if (json.isEmpty()) {
            return Optional.empty();
        }
        try {
            Stored s = mapper.readValue(json.get(), Stored.class);
            // When the ticket was issued while impersonating, the WebSocket handshake carries the real admin's session.
            long expected = s.actorUserId() != null ? s.actorUserId() : s.userId();
            if (presentedUserId != null && presentedUserId != expected) {
                return Optional.empty();
            }
            return Optional.of(new TailTicketData(s.userId(), s.accountIds(), s.query(), java.time.Instant.ofEpochMilli(s.issuedAtMs()), s.actorUserId()));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    record Stored(long userId, List<Long> accountIds, String query, long issuedAtMs, Long actorUserId) {}
}
