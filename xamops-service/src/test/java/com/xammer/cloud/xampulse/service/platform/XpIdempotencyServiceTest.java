package com.xammer.cloud.xampulse.service.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import com.xammer.cloud.xampulse.service.stream.TicketStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class XpIdempotencyServiceTest {

    record Req(String name) {}
    record Res(long id, String name) {}

    static final class MemoryStore implements TicketStore {
        final Map<String, String> data = new HashMap<>();
        Duration lastTtl;
        @Override public void put(String k, String v, Duration ttl) { data.put(k, v); lastTtl = ttl; }
        @Override public Optional<String> getAndDelete(String k) { return Optional.ofNullable(data.remove(k)); }
        @Override public Optional<String> get(String k) { return Optional.ofNullable(data.get(k)); }
        @Override public boolean putIfAbsent(String k, String v, Duration ttl) { lastTtl = ttl; return data.putIfAbsent(k, v) == null; }
    }

    private final MemoryStore store = new MemoryStore();
    private final XpIdempotencyService svc = new XpIdempotencyService(store, new ObjectMapper().registerModule(new JavaTimeModule()));
    private final AtomicInteger runs = new AtomicInteger();

    private Res create(String name) {
        return new Res(runs.incrementAndGet(), name);
    }

    @Test
    void firstCallRunsAndReplayReturnsTheSameResponseWithoutRunningAgain() {
        Res first = svc.execute(1, "POST /x", "key-12345678", new Req("a"), Res.class, () -> create("a"));
        Res replay = svc.execute(1, "POST /x", "key-12345678", new Req("a"), Res.class, () -> create("a"));
        assertEquals(first, replay);
        assertEquals(1, runs.get());
        assertEquals(Duration.ofMinutes(10), store.lastTtl);
    }

    @Test
    void differentBodyWithTheSameKeyIsA409() {
        svc.execute(1, "POST /x", "key-12345678", new Req("a"), Res.class, () -> create("a"));
        XamPulseException e = assertThrows(XamPulseException.class,
                () -> svc.execute(1, "POST /x", "key-12345678", new Req("b"), Res.class, () -> create("b")));
        assertEquals("XP_IDEMPOTENCY_KEY_REUSED", e.getCode());
        assertEquals(409, e.getHttpStatus());
        assertEquals(1, runs.get());
    }

    @Test
    void aSecondCallWhileTheFirstIsStillRunningIsA409() {
        XamPulseException[] inner = new XamPulseException[1];
        svc.execute(1, "POST /x", "key-12345678", new Req("a"), Res.class, () -> {
            inner[0] = assertThrows(XamPulseException.class,
                    () -> svc.execute(1, "POST /x", "key-12345678", new Req("a"), Res.class, () -> create("a")));
            return create("a");
        });
        assertEquals("XP_STATE_CONFLICT", inner[0].getCode());
        assertEquals(1, runs.get());
    }

    @Test
    void aFailedActionClearsTheKeySoARetryRuns() {
        assertThrows(IllegalStateException.class, () -> svc.execute(1, "POST /x", "key-12345678", new Req("a"), Res.class,
                () -> { throw new IllegalStateException("boom"); }));
        Res ok = svc.execute(1, "POST /x", "key-12345678", new Req("a"), Res.class, () -> create("a"));
        assertEquals("a", ok.name());
    }

    @Test
    void badKeyFormatIsA400AndNoKeyJustRuns() {
        for (String bad : new String[] {"short", "has space in it", "x".repeat(81), "semi;colon;key"}) {
            XamPulseException e = assertThrows(XamPulseException.class,
                    () -> svc.execute(1, "POST /x", bad, new Req("a"), Res.class, () -> create("a")), bad);
            assertEquals("XP_INVALID_REQUEST", e.getCode());
        }
        svc.execute(1, "POST /x", null, new Req("a"), Res.class, () -> create("a"));
        svc.execute(1, "POST /x", null, new Req("a"), Res.class, () -> create("a"));
        assertEquals(2, runs.get());
        assertTrue(store.data.isEmpty());
    }

    @Test
    void usersAndRoutesDoNotCollide() {
        svc.execute(1, "POST /x", "key-12345678", new Req("a"), Res.class, () -> create("a"));
        svc.execute(2, "POST /x", "key-12345678", new Req("a"), Res.class, () -> create("a"));
        svc.execute(1, "POST /y", "key-12345678", new Req("a"), Res.class, () -> create("a"));
        assertEquals(3, runs.get());
        assertTrue(store.data.keySet().stream().noneMatch(k -> k.contains("key-12345678")), "raw key is never stored");
    }
}
