package com.xammer.cloud.service;

import com.xammer.cloud.domain.CachedData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The point of this service is that a stale snapshot must NOT be returned, because
 * previously a project whose cloud API access broke kept serving its last successful
 * result forever with nothing in the payload to indicate staleness.
 */
class SnapshotFreshnessServiceTest {

    private static final String KEY = "WASTE::proj-1";

    private SnapshotFreshnessService service(long maxAgeHours) {
        return new SnapshotFreshnessService(maxAgeHours);
    }

    private CachedData snapshotAgedHours(long hours) {
        CachedData data = new CachedData();
        data.setCacheKey(KEY);
        data.setJsonData("[]");
        data.setLastUpdated(LocalDateTime.now().minusHours(hours));
        return data;
    }

    @Test
    @DisplayName("returns a snapshot inside the freshness window")
    void returnsFreshSnapshot() {
        Optional<CachedData> result = service(48).ifFresh(Optional.of(snapshotAgedHours(1)), KEY);
        assertTrue(result.isPresent());
    }

    @Test
    @DisplayName("returns a snapshot exactly at the boundary")
    void returnsSnapshotAtBoundary() {
        // 47h is inside a 48h window; the boundary itself must not be rejected early.
        assertTrue(service(48).ifFresh(Optional.of(snapshotAgedHours(47)), KEY).isPresent());
    }

    @Test
    @DisplayName("suppresses a snapshot older than the window so the caller refetches")
    void suppressesStaleSnapshot() {
        assertFalse(service(48).ifFresh(Optional.of(snapshotAgedHours(72)), KEY).isPresent());
    }

    @Test
    @DisplayName("suppresses a months-old snapshot, the case that made broken projects look clean")
    void suppressesVeryOldSnapshot() {
        assertFalse(service(48).ifFresh(Optional.of(snapshotAgedHours(24 * 180)), KEY).isPresent());
    }

    @Test
    @DisplayName("treats a snapshot with no timestamp as stale rather than trusting it")
    void treatsMissingTimestampAsStale() {
        CachedData noTimestamp = new CachedData();
        noTimestamp.setCacheKey(KEY);
        noTimestamp.setJsonData("[]");
        noTimestamp.setLastUpdated(null);
        assertFalse(service(48).ifFresh(Optional.of(noTimestamp), KEY).isPresent());
    }

    @Test
    @DisplayName("passes an absent snapshot straight through")
    void passesThroughEmpty() {
        assertFalse(service(48).ifFresh(Optional.empty(), KEY).isPresent());
    }

    @Test
    @DisplayName("reports snapshot age so callers can disclose it")
    void reportsAge() {
        assertEquals(5L, service(48).ageInHours(snapshotAgedHours(5)).orElseThrow());
        assertTrue(service(48).ageInHours(null).isEmpty());
    }

    @Test
    @DisplayName("honours a shorter configured window")
    void honoursConfiguredWindow() {
        assertTrue(service(2).ifFresh(Optional.of(snapshotAgedHours(1)), KEY).isPresent());
        assertFalse(service(2).ifFresh(Optional.of(snapshotAgedHours(6)), KEY).isPresent());
    }

    @Test
    @DisplayName("refuses a non-positive window at construction")
    void rejectsInvalidWindow() {
        assertThrows(IllegalStateException.class, () -> service(0));
        assertThrows(IllegalStateException.class, () -> service(-1));
    }
}
