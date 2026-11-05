package com.xammer.cloud.xampulse.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.xammer.cloud.xampulse.service.platform.AuditService;
import com.xammer.cloud.xampulse.service.query.ClickHouseExecutor;
import com.xammer.cloud.xampulse.service.stream.TailAccessRevalidator;
import com.xammer.cloud.xampulse.service.stream.TailAccessRevalidator.Verdict;
import com.xammer.cloud.xampulse.service.stream.TailSessionRegistry;
import com.xammer.cloud.xampulse.service.stream.TailTicketData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class XamPulseTailWebSocketHandlerTest {

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-03-10T12:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final MutableClock clock = new MutableClock();
    private final TailSessionRegistry registry = new TailSessionRegistry();
    private final AuditService audit = mock(AuditService.class);
    private final List<String> sent = new ArrayList<>();
    private final AtomicInteger checks = new AtomicInteger();
    private Verdict verdict = Verdict.ALLOWED;
    private Long seenActor;
    private XamPulseTailWebSocketHandler handler;
    private WebSocketSession session;

    @BeforeEach
    void setUp() throws Exception {
        TailAccessRevalidator revalidator = (user, actor, accounts) -> {
            checks.incrementAndGet();
            seenActor = actor;
            return verdict;
        };
        // A huge initial delay keeps the real scheduler out of the way: the tests drive ticks by hand.
        handler = new XamPulseTailWebSocketHandler(mock(ClickHouseExecutor.class), registry, audit, mapper, revalidator, clock, 3600);
        session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s1");
        when(session.isOpen()).thenReturn(true);
        doAnswer(inv -> {
            sent.add(((TextMessage) inv.<WebSocketMessage<?>>getArgument(0)).getPayload());
            return null;
        }).when(session).sendMessage(any());
    }

    @AfterEach
    void tearDown() {
        handler.shutdown();
    }

    private void open(Long actor) throws Exception {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(XamPulseTailHandshakeInterceptor.ATTR_TICKET,
                new TailTicketData(5L, List.of(7L), "severity:error", clock.instant(), actor));
        when(session.getAttributes()).thenReturn(attrs);
        handler.afterConnectionEstablished(session);
    }

    private void advanceAndTick(long seconds) {
        clock.now = clock.now.plusSeconds(seconds);
        handler.tickNow(session);
    }

    private boolean sentEnd(String reason) {
        return sent.stream().anyMatch(m -> m.contains("\"end\"") && m.contains(reason));
    }

    @Test
    void allowedSessionStaysOpenPastTheRevalidationInterval() throws Exception {
        open(null);
        advanceAndTick(10);
        assertEquals(0, checks.get(), "no check before 30 s");
        advanceAndTick(25);
        advanceAndTick(31);
        assertEquals(2, checks.get());
        verify(session, never()).close(any(CloseStatus.class));
        assertEquals(1, registry.count());
    }

    @Test
    void deniedEndsTheSessionWithRevokedAndReleasesTheSlot() throws Exception {
        open(null);
        verdict = Verdict.DENIED;
        advanceAndTick(31);
        assertTrue(sentEnd("REVOKED"), sent.toString());
        verify(session).close(CloseStatus.NORMAL);
        assertEquals(0, registry.count());
        verify(audit).recordForUser(eq(5L), eq(7L), eq("logs.tail.close"), any(), any());
    }

    @Test
    void oneUnknownKeepsTheSessionAndThreeInARowEndItWithError() throws Exception {
        open(null);
        verdict = Verdict.UNKNOWN;
        advanceAndTick(31);
        advanceAndTick(31);
        assertFalse(sentEnd("ERROR"));
        verdict = Verdict.ALLOWED;
        advanceAndTick(31); // an inconclusive streak is reset by a clean check
        verdict = Verdict.UNKNOWN;
        advanceAndTick(31);
        advanceAndTick(31);
        assertFalse(sentEnd("ERROR"));
        advanceAndTick(31);
        assertTrue(sentEnd("ERROR"), sent.toString());
        assertEquals(0, registry.count());
    }

    @Test
    void impersonatedOpenIsAuditedWithTheRealActorAndRevalidatedAgainstIt() throws Exception {
        open(99L);
        verify(audit).recordForUser(eq(5L), eq(7L), eq("logs.tail.impersonated"), eq("actor=99"), any());
        verify(audit).recordForUser(eq(5L), eq(7L), eq("logs.tail.open"),
                org.mockito.ArgumentMatchers.endsWith(",actor=99"), any());
        advanceAndTick(31);
        assertEquals(99L, seenActor);
    }

    @Test
    void anOrdinaryOpenWritesNoImpersonationAudit() throws Exception {
        open(null);
        verify(audit, never()).recordForUser(any(), any(), eq("logs.tail.impersonated"), any(), any());
    }
}
