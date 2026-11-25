package com.xammer.cloud.xampulse.service.archive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.xammer.cloud.xampulse.dto.XpArchiveEstimate;
import com.xammer.cloud.xampulse.dto.XpArchiveSearchJob;
import com.xammer.cloud.xampulse.service.stream.TicketStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class ArchiveSearchJobStoreTest {

    public static final class MemoryStore implements TicketStore {
        final Map<String, String> data = new HashMap<>();
        final Map<String, Duration> ttls = new HashMap<>();
        @Override public void put(String k, String v, Duration ttl) { data.put(k, v); ttls.put(k, ttl); }
        @Override public Optional<String> getAndDelete(String k) { return Optional.ofNullable(data.remove(k)); }
        @Override public Optional<String> get(String k) { return Optional.ofNullable(data.get(k)); }
        @Override public boolean putIfAbsent(String k, String v, Duration ttl) { ttls.put(k, ttl); return data.putIfAbsent(k, v) == null; }
    }

    private final MemoryStore memory = new MemoryStore();
    private final ArchiveSearchJobStore store =
            new ArchiveSearchJobStore(memory, new ObjectMapper().registerModule(new JavaTimeModule()));
    private final Instant t = Instant.parse("2026-11-02T10:00:00Z");

    private XpArchiveSearchJob job(String id, String state) {
        return new XpArchiveSearchJob(id, state, new XpArchiveEstimate(10L, 1L, 0.0, List.of("STANDARD"), "ESTIMATE"),
                0, 1, null, null, null, t, null);
    }

    @Test
    void idsAre128BitUrlSafeAndUnique() {
        String a = store.newId();
        assertEquals(22, a.length());
        assertTrue(a.matches("^[A-Za-z0-9_-]{22}$"));
        assertNotEquals(a, store.newId());
    }

    @Test
    void roundTripKeepsOwnerAccountAndJobAndExpiresAfterAnHour() {
        String id = store.newId();
        store.save(new ArchiveSearchJobStore.Record(5L, 7L, job(id, "QUEUED")));
        var rec = store.find(id).orElseThrow();
        assertEquals(5L, rec.ownerUserId());
        assertEquals(7L, rec.accountId());
        assertEquals("QUEUED", rec.job().state());
        assertEquals(t, rec.job().createdAt());
        assertEquals(Duration.ofHours(1), memory.ttls.get("xp:arch:" + id));
    }

    @Test
    void malformedIdsAndUnknownIdsAreEmpty() {
        assertTrue(store.find(null).isEmpty());
        assertTrue(store.find("../../x").isEmpty());
        assertTrue(store.find(store.newId()).isEmpty());
    }

    @Test
    void aCancelledJobIsNotOverwrittenByALateRunner() {
        String id = store.newId();
        store.save(new ArchiveSearchJobStore.Record(5L, 7L, job(id, "CANCELLED")));
        store.update(id, j -> job(id, "DONE"));
        assertEquals("CANCELLED", store.find(id).orElseThrow().job().state());
        assertTrue(store.isCancelled(id));
    }

    @Test
    void oneLockPerAccount() {
        assertTrue(store.tryLock(7L, "a"));
        assertFalse(store.tryLock(7L, "b"));
        assertTrue(store.tryLock(8L, "c"));
        store.unlock(7L);
        assertTrue(store.tryLock(7L, "d"));
    }
}
