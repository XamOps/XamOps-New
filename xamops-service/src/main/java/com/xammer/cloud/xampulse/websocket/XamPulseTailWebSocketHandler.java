package com.xammer.cloud.xampulse.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.xampulse.dto.XpLogRow;
import com.xammer.cloud.xampulse.dto.XpTailClientFrame;
import com.xammer.cloud.xampulse.dto.XpTailServerFrame;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import com.xammer.cloud.xampulse.service.platform.AuditService;
import com.xammer.cloud.xampulse.service.query.ClickHouseExecutor;
import com.xammer.cloud.xampulse.service.query.FilterAst;
import com.xammer.cloud.xampulse.service.query.FilterDslParser;
import com.xammer.cloud.xampulse.service.stream.TailAccessRevalidator;
import com.xammer.cloud.xampulse.service.stream.TailPoller;
import com.xammer.cloud.xampulse.service.stream.TailSessionRegistry;
import com.xammer.cloud.xampulse.service.stream.TailTicketData;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Raw WebSocket live tail ({@code /xampulse-ws/tail}). One ClickHouse poll per second per session; rows are sent in
 * chunks of at most 500 every 250 ms. Limits: idle 15 min (warning at 14), maximum session 2 h, 3 per user and
 * 10 per account (close code 4429 when exceeded). Access is revalidated every 30 s: a revoked user ends the session
 * with {@code End(REVOKED)}; three consecutive inconclusive checks end it with {@code End(ERROR)}.
 */
@Component("xpTailWebSocketHandler")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class XamPulseTailWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(XamPulseTailWebSocketHandler.class);
    static final Duration IDLE_LIMIT = Duration.ofMinutes(15);
    static final Duration IDLE_WARNING = Duration.ofMinutes(14);
    static final Duration MAX_SESSION = Duration.ofHours(2);
    static final int CHUNK = 500;
    static final Duration REVALIDATE_EVERY = Duration.ofSeconds(30);
    static final int MAX_UNKNOWN_STREAK = 3;

    private final ClickHouseExecutor executor;
    private final TailSessionRegistry registry;
    private final AuditService audit;
    private final ObjectMapper mapper;
    private final TailAccessRevalidator revalidator;
    private final Clock clock;
    private final long tickInitialDelaySec;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "xp-tail");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Live> live = new ConcurrentHashMap<>();

    private static final class Live {
        final TailTicketData data;
        final TailPoller poller;
        final Instant openedAt;
        volatile Instant lastClientActivity;
        volatile boolean paused;
        volatile boolean warned;
        volatile Instant lastCheckedAt;
        int unknownStreak;
        long matched;
        long dropped;
        int polls;
        ScheduledFuture<?> task;

        Live(TailTicketData data, TailPoller poller, Instant now) {
            this.data = data;
            this.poller = poller;
            this.openedAt = now;
            this.lastClientActivity = now;
            this.lastCheckedAt = now;
        }
    }

    @Autowired
    public XamPulseTailWebSocketHandler(ClickHouseExecutor executor, TailSessionRegistry registry, AuditService audit,
                                        ObjectMapper objectMapper, TailAccessRevalidator revalidator) {
        this(executor, registry, audit, objectMapper, revalidator, Clock.systemUTC(), 1);
    }

    /** For tests: drive time and the tick manually. */
    XamPulseTailWebSocketHandler(ClickHouseExecutor executor, TailSessionRegistry registry, AuditService audit,
                                 ObjectMapper objectMapper, TailAccessRevalidator revalidator, Clock clock, long tickInitialDelaySec) {
        this.executor = executor;
        this.registry = registry;
        this.audit = audit;
        this.mapper = objectMapper;
        this.revalidator = revalidator;
        this.clock = clock;
        this.tickInitialDelaySec = tickInitialDelaySec;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Object attr = session.getAttributes().get(XamPulseTailHandshakeInterceptor.ATTR_TICKET);
        if (!(attr instanceof TailTicketData data)) {
            session.close(new CloseStatus(4401, "Invalid ticket"));
            return;
        }
        if (!registry.tryRegister(session.getId(), data.userId(), data.accountIds())) {
            session.close(new CloseStatus(4429, "Too many live tail sessions"));
            return;
        }
        FilterAst filter = FilterDslParser.parse(data.query());
        Live l = new Live(data, new TailPoller(executor::query, data.accountIds(), filter, clock), clock.instant());
        live.put(session.getId(), l);
        Long firstAccount = data.accountIds().isEmpty() ? null : data.accountIds().get(0);
        audit.recordForUser(data.userId(), firstAccount, "logs.tail.open",
                AuditService.describeQuery(data.query()) + actorSuffix(data), null);
        if (data.actorUserId() != null) {
            // Support access is reviewable: who acted as whom.
            audit.recordForUser(data.userId(), firstAccount, "logs.tail.impersonated", "actor=" + data.actorUserId(), null);
        }
        l.task = scheduler.scheduleWithFixedDelay(() -> tick(session, l), tickInitialDelaySec, 1, TimeUnit.SECONDS);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Live l = live.get(session.getId());
        if (l == null) {
            return;
        }
        l.lastClientActivity = clock.instant();
        l.warned = false;
        XpTailClientFrame frame;
        try {
            frame = mapper.readValue(message.getPayload(), XpTailClientFrame.class);
        } catch (JsonProcessingException e) {
            return; // ignore malformed frames; the connection stays up
        }
        switch (frame) {
            case XpTailClientFrame.Pause p -> l.paused = true;
            case XpTailClientFrame.Resume r -> l.paused = false;
            case XpTailClientFrame.Ping p -> { /* activity already recorded */ }
            case XpTailClientFrame.Query q -> {
                try {
                    l.poller.setFilter(FilterDslParser.parse(q.query()));
                    send(session, new XpTailServerFrame.Notice("QUERY_REVALIDATED", "Filter updated"));
                } catch (XamPulseException e) {
                    send(session, new XpTailServerFrame.Notice("QUERY_REVALIDATED", "Filter rejected: " + e.getMessage()));
                }
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        cleanup(session.getId(), true);
    }

    /** Runs one tick for a live session (tests). */
    void tickNow(WebSocketSession session) {
        Live l = live.get(session.getId());
        if (l != null) {
            tick(session, l);
        }
    }

    private static String actorSuffix(TailTicketData d) {
        return d.actorUserId() == null ? "" : ",actor=" + d.actorUserId();
    }

    private void tick(WebSocketSession session, Live l) {
        try {
            if (!session.isOpen()) {
                cleanup(session.getId(), false);
                return;
            }
            Instant now = clock.instant();
            if (Duration.between(l.openedAt, now).compareTo(MAX_SESSION) >= 0) {
                end(session, "MAX_DURATION", "The live tail ran for 2 hours. Start it again to continue.");
                return;
            }
            Duration idle = Duration.between(l.lastClientActivity, now);
            if (idle.compareTo(IDLE_LIMIT) >= 0) {
                end(session, "IDLE_TIMEOUT", "Stopped after 15 minutes without activity.");
                return;
            }
            if (idle.compareTo(IDLE_WARNING) >= 0 && !l.warned) {
                l.warned = true;
                send(session, new XpTailServerFrame.Notice("IDLE_WARNING", "The live tail will stop in about a minute."));
            }
            if (Duration.between(l.lastCheckedAt, now).compareTo(REVALIDATE_EVERY) >= 0 && !revalidate(session, l, now)) {
                return;
            }
            if (l.paused) {
                return;
            }
            TailPoller.Batch b = l.poller.poll();
            l.matched += b.rows().size();
            l.dropped += b.dropped();
            if (b.capped()) {
                send(session, new XpTailServerFrame.Notice("RATE_CAPPED", "Showing a sample: more than 2000 lines per second matched."));
            }
            List<XpLogRow> rows = b.rows();
            for (int i = 0, chunk = 0; i < rows.size(); i += CHUNK, chunk++) {
                List<XpLogRow> part = rows.subList(i, Math.min(rows.size(), i + CHUNK));
                String cursor = String.valueOf(b.cursorMs());
                scheduler.schedule(() -> send(session, new XpTailServerFrame.Rows(part, cursor, part.size())),
                        chunk * 250L, TimeUnit.MILLISECONDS);
            }
            if (++l.polls % 10 == 0) {
                send(session, new XpTailServerFrame.Stats(l.matched, l.dropped));
            }
        } catch (XamPulseException e) {
            end(session, "ERROR", e.getMessage());
        } catch (RuntimeException e) {
            log.warn("XamPulse tail tick failed: {}", e.getClass().getSimpleName());
            end(session, "ERROR", "The live tail hit an error and stopped.");
        }
    }

    /** @return false when the session was ended. */
    private boolean revalidate(WebSocketSession session, Live l, Instant now) {
        l.lastCheckedAt = now;
        TailAccessRevalidator.Verdict v;
        try {
            v = revalidator.check(l.data.userId(), l.data.actorUserId(), l.data.accountIds());
        } catch (RuntimeException e) {
            v = TailAccessRevalidator.Verdict.UNKNOWN;
        }
        switch (v) {
            case DENIED -> {
                end(session, "REVOKED", "Your access to this account changed. The live tail stopped.");
                return false;
            }
            case UNKNOWN -> {
                if (++l.unknownStreak >= MAX_UNKNOWN_STREAK) {
                    end(session, "ERROR", "The live tail could not confirm your access and stopped.");
                    return false;
                }
            }
            default -> l.unknownStreak = 0;
        }
        return true;
    }

    private void end(WebSocketSession session, String reason, String message) {
        send(session, new XpTailServerFrame.End(reason, message));
        try {
            session.close(CloseStatus.NORMAL);
        } catch (IOException ignored) {
            // already closed
        }
        cleanup(session.getId(), true);
    }

    private void send(WebSocketSession session, XpTailServerFrame frame) {
        try {
            if (session.isOpen()) {
                synchronized (session) {
                    session.sendMessage(new TextMessage(mapper.writeValueAsString(frame)));
                }
            }
        } catch (IOException | RuntimeException e) {
            log.debug("XamPulse tail send failed: {}", e.getClass().getSimpleName());
        }
    }

    private void cleanup(String sessionId, boolean audited) {
        Live l = live.remove(sessionId);
        registry.release(sessionId);
        if (l != null) {
            if (l.task != null) {
                l.task.cancel(false);
            }
            if (audited) {
                audit.recordForUser(l.data.userId(), l.data.accountIds().isEmpty() ? null : l.data.accountIds().get(0),
                        "logs.tail.close", "matched=" + l.matched + ",dropped=" + l.dropped + actorSuffix(l.data), null);
            }
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
