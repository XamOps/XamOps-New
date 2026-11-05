package com.xammer.cloud.xampulse.service.stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.xammer.cloud.xampulse.dto.XpLogRow;
import com.xammer.cloud.xampulse.dto.XpSeverity;
import com.xammer.cloud.xampulse.dto.XpTailClientFrame;
import com.xammer.cloud.xampulse.dto.XpTailServerFrame;
import com.xammer.cloud.xampulse.service.query.FilterDslParser;
import com.xammer.cloud.xampulse.service.query.ScopedSql;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TailTests {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .configure(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

    // ---------------- tickets ----------------

    static final class MemoryStore implements TicketStore {
        final Map<String, String> data = new HashMap<>();
        Duration lastTtl;

        @Override public void put(String key, String value, Duration ttl) { data.put(key, value); lastTtl = ttl; }

        @Override public Optional<String> getAndDelete(String key) { return Optional.ofNullable(data.remove(key)); }
        @Override public Optional<String> get(String key) { return Optional.ofNullable(data.get(key)); }
        @Override public boolean putIfAbsent(String key, String value, Duration ttl) { return data.putIfAbsent(key, value) == null; }
    }

    private TailTicketData data(long user) {
        return new TailTicketData(user, List.of(7L), "severity:error", Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void ticketIsSingleUseShortLivedAndBoundToItsUserAndAccounts() {
        MemoryStore store = new MemoryStore();
        TailTicketService svc = new TailTicketService(store, mapper);
        String t = svc.issue(data(5));
        assertEquals(22, t.length(), "128 bits, base64url");
        assertEquals(Duration.ofSeconds(30), store.lastTtl);
        TailTicketData got = svc.consume(t, 5L).orElseThrow();
        assertEquals(List.of(7L), got.accountIds());
        assertEquals("severity:error", got.query());
        assertTrue(svc.consume(t, 5L).isEmpty(), "second use fails");
    }

    @Test
    void anotherUserPresentingTheTicketIsRefusedAndTheTicketIsBurned() {
        TailTicketService svc = new TailTicketService(new MemoryStore(), mapper);
        String t = svc.issue(data(5));
        assertTrue(svc.consume(t, 6L).isEmpty());
        assertTrue(svc.consume(t, 5L).isEmpty(), "burned for the rightful owner too: fail closed");
    }

    @Test
    void impersonatedTicketRoundTripsTheActorAndIsAcceptedFromTheAdminSessionOnly() {
        TailTicketService svc = new TailTicketService(new MemoryStore(), mapper);
        TailTicketData issued = new TailTicketData(5, List.of(7L), "", Instant.parse("2026-01-01T00:00:00Z"), 99L);
        String t = svc.issue(issued);
        TailTicketData got = svc.consume(t, 99L).orElseThrow();
        assertEquals(99L, got.actorUserId());
        assertEquals(5L, got.userId());
        // The impersonated user's own id is not what the handshake presents: refused and burned.
        String t2 = svc.issue(issued);
        assertTrue(svc.consume(t2, 5L).isEmpty());
        assertTrue(svc.consume(t2, 99L).isEmpty());
        // A third party is refused as well.
        assertTrue(svc.consume(svc.issue(issued), 6L).isEmpty());
    }

    @Test
    void ticketWithoutActorBehavesAsBefore() {
        TailTicketService svc = new TailTicketService(new MemoryStore(), mapper);
        assertNull(svc.consume(svc.issue(data(5)), 5L).orElseThrow().actorUserId());
    }

    @Test
    void malformedAndUnknownTicketsAreRefused() {
        TailTicketService svc = new TailTicketService(new MemoryStore(), mapper);
        for (String bad : new String[] {null, "", "short", "x".repeat(22), "../../etc/passwd...........", "a b c d e f g h i j k l"}) {
            assertTrue(svc.consume(bad, null).isEmpty(), String.valueOf(bad));
        }
    }

    @Test
    void ticketsAreUnique() {
        TailTicketService svc = new TailTicketService(new MemoryStore(), mapper);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 500; i++) {
            assertTrue(seen.add(svc.issue(data(1))));
        }
    }

    // ---------------- session limits ----------------

    @Test
    void limitsAreThreePerUserAndTenPerAccount() {
        TailSessionRegistry r = new TailSessionRegistry();
        assertTrue(r.tryRegister("s1", 1, List.of(7L)));
        assertTrue(r.tryRegister("s2", 1, List.of(7L)));
        assertTrue(r.tryRegister("s3", 1, List.of(7L)));
        assertFalse(r.tryRegister("s4", 1, List.of(7L)), "4th for the same user");
        r.release("s1");
        assertTrue(r.tryRegister("s4", 1, List.of(7L)), "slot freed");
        for (int u = 2; u <= 4; u++) {
            for (int k = 0; k < 3; k++) {
                r.tryRegister("u" + u + "-" + k, u, List.of(8L));
            }
        }
        // account 8 already has 9 sessions; the 10th is allowed, the 11th is not
        assertTrue(r.tryRegister("u5-0", 5, List.of(8L)));
        assertFalse(r.tryRegister("u6-0", 6, List.of(8L)));
        assertEquals(3 + 9 + 1, r.count());
    }

    // ---------------- poller ----------------

    static final class Source {
        final List<Map<String, Object>> rows = new ArrayList<>();
        final List<ScopedSql> seen = new ArrayList<>();

        List<Map<String, Object>> run(ScopedSql q) {
            seen.add(q);
            long since = (Long) q.params().get(q.params().size() - 2);
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> r : rows) {
                if (((Long) r.get("ingest_ms")) > since) {
                    out.add(r);
                }
            }
            return out;
        }
    }

    static Map<String, Object> row(long tenant, long ingestMs, String body) {
        Map<String, Object> r = new HashMap<>();
        r.put("tenant_id", tenant);
        r.put("ts_ms", ingestMs - 5);
        r.put("ingest_ms", ingestMs);
        r.put("region", "us-east-1");
        r.put("instance_id", "i-1");
        r.put("service", "api");
        r.put("source", "file");
        r.put("severity", "ERROR");
        r.put("body", body);
        r.put("attrs", Map.of());
        r.put("trace_id", "");
        r.put("container_id", "");
        r.put("body_hash", String.valueOf(Math.abs(body.hashCode())));
        return r;
    }

    private final Instant t0 = Instant.parse("2026-01-01T00:00:10Z");

    @Test
    void pollerDeliversNewRowsOnceAndAdvancesTheCursor() {
        Source src = new Source();
        Clock clock = Clock.fixed(t0, ZoneOffset.UTC);
        TailPoller p = new TailPoller(src::run, List.of(7L), FilterDslParser.parse("severity:error"), clock);
        assertEquals(t0.toEpochMilli() - 2000, p.cursor());
        assertTrue(p.poll().rows().isEmpty());
        src.rows.add(row(7, t0.toEpochMilli() - 500, "a"));
        src.rows.add(row(7, t0.toEpochMilli() - 400, "b"));
        TailPoller.Batch b1 = p.poll();
        assertEquals(2, b1.rows().size());
        assertEquals(t0.toEpochMilli() - 400, b1.cursorMs());
        assertEquals(0, p.poll().rows().size(), "no repeats");
        src.rows.add(row(7, t0.toEpochMilli() - 300, "c"));
        assertEquals(List.of("c"), p.poll().rows().stream().map(XpLogRow::body).toList());
    }

    @Test
    void rowsSharingTheCursorMillisecondAreNeitherLostNorRepeated() {
        Source src = new Source();
        TailPoller p = new TailPoller(src::run, List.of(7L), FilterDslParser.parse(""), Clock.fixed(t0, ZoneOffset.UTC));
        long ms = t0.toEpochMilli() - 100;
        src.rows.add(row(7, ms, "first"));
        assertEquals(1, p.poll().rows().size());
        src.rows.add(row(7, ms, "second")); // lands later with the same millisecond
        List<XpLogRow> next = p.poll().rows();
        assertEquals(List.of("second"), next.stream().map(XpLogRow::body).toList());
        assertEquals(0, p.poll().rows().size());
    }

    @Test
    void overTheCapRowsAreSampledAndTheDropIsReportedNotHidden() {
        Source src = new Source();
        TailPoller p = new TailPoller(src::run, List.of(7L), FilterDslParser.parse(""), Clock.fixed(t0, ZoneOffset.UTC));
        for (int i = 0; i < TailPoller.MAX_ROWS_PER_POLL + 300; i++) {
            src.rows.add(row(7, t0.toEpochMilli() - 1000 + (i % 900), "line-" + i));
        }
        TailPoller.Batch b = p.poll();
        assertEquals(TailPoller.MAX_ROWS_PER_POLL, b.rows().size());
        assertTrue(b.capped());
        assertTrue(b.dropped() >= 300);
    }

    @Test
    void pollerQueriesEachAccountSeparatelyAndOnlyWithItsOwnTenantFirst() {
        Source src = new Source();
        TailPoller p = new TailPoller(src::run, List.of(7L, 8L), FilterDslParser.parse(""), Clock.fixed(t0, ZoneOffset.UTC));
        p.poll();
        assertEquals(2, src.seen.size());
        assertEquals(List.of(7L), src.seen.get(0).tenantIds());
        assertEquals(List.of(8L), src.seen.get(1).tenantIds());
        assertEquals(7L, src.seen.get(0).params().get(0));
        assertEquals(8L, src.seen.get(1).params().get(0));
    }

    @Test
    void changingTheFilterIsReflectedInTheNextPoll() {
        Source src = new Source();
        TailPoller p = new TailPoller(src::run, List.of(7L), FilterDslParser.parse(""), Clock.fixed(t0, ZoneOffset.UTC));
        p.poll();
        p.setFilter(FilterDslParser.parse("service:billing"));
        p.poll();
        assertTrue(src.seen.get(1).params().contains("billing"));
        assertFalse(src.seen.get(0).params().contains("billing"));
    }

    // ---------------- frame shapes ----------------

    @Test
    void serverFramesSerialiseExactlyAsInTheContract() throws Exception {
        XpLogRow row = new XpLogRow("id1", Instant.parse("2026-01-01T00:00:00Z"), 7L, "us-east-1", "i-1", "api", "file",
                XpSeverity.ERROR, "boom", Map.of("k", "v"), null, null);
        JsonNode rows = mapper.readTree(mapper.writeValueAsString(new XpTailServerFrame.Rows(List.of(row), "123", 2.0)));
        assertEquals("rows", rows.get("t").asText());
        assertEquals("123", rows.get("cursor").asText());
        assertEquals(2.0, rows.get("rate").asDouble());
        assertEquals("ERROR", rows.get("rows").get(0).get("severity").asText());
        assertEquals("2026-01-01T00:00:00Z", rows.get("rows").get(0).get("ts").asText());
        assertFalse(rows.get("rows").get(0).has("traceId"), "null fields are omitted");

        JsonNode stats = mapper.readTree(mapper.writeValueAsString(new XpTailServerFrame.Stats(10, 2)));
        assertEquals("stats", stats.get("t").asText());
        assertEquals(10, stats.get("matched").asInt());
        assertEquals(2, stats.get("dropped").asInt());

        JsonNode notice = mapper.readTree(mapper.writeValueAsString(new XpTailServerFrame.Notice("RATE_CAPPED", null)));
        assertEquals("notice", notice.get("t").asText());
        assertEquals("RATE_CAPPED", notice.get("code").asText());
        assertFalse(notice.has("message"));

        JsonNode end = mapper.readTree(mapper.writeValueAsString(new XpTailServerFrame.End("IDLE_TIMEOUT", "bye")));
        assertEquals("end", end.get("t").asText());
        assertEquals("IDLE_TIMEOUT", end.get("reason").asText());
    }

    @Test
    void clientFramesParseFromTheContractShapes() throws Exception {
        assertInstanceOf(XpTailClientFrame.Pause.class, mapper.readValue("{\"t\":\"pause\"}", XpTailClientFrame.class));
        assertInstanceOf(XpTailClientFrame.Resume.class, mapper.readValue("{\"t\":\"resume\"}", XpTailClientFrame.class));
        assertInstanceOf(XpTailClientFrame.Ping.class, mapper.readValue("{\"t\":\"ping\"}", XpTailClientFrame.class));
        XpTailClientFrame.Query q = (XpTailClientFrame.Query) mapper.readValue("{\"t\":\"query\",\"query\":\"service:api\"}", XpTailClientFrame.class);
        assertEquals("service:api", q.query());
        assertThrows(Exception.class, () -> mapper.readValue("{\"t\":\"shell\"}", XpTailClientFrame.class));
    }
}
