package com.xammer.cloud.xampulse.service.insight;

import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EventWriterTest {

    private final Instant ts = Instant.parse("2026-02-02T12:00:00Z");

    @Test
    void insertsOneRowWithTheExpectedShape() throws Exception {
        FakeClickHouseWrites writes = new FakeClickHouseWrites();
        new EventWriter(writes).record(7L, "ALERT", "i-1", "High CPU firing", "value 0.95", ts);

        assertEquals(1, writes.calls.size());
        FakeClickHouseWrites.Call call = writes.calls.get(0);
        assertEquals("xp_events", call.table());
        assertEquals(List.of("tenant_id", "ts", "kind", "resource", "title", "detail"), call.columns());
        assertEquals(1, call.rows().size());
        List<Object> row = call.rows().get(0);
        assertEquals(7L, row.get(0));
        assertEquals(Timestamp.from(ts), row.get(1));
        assertEquals("ALERT", row.get(2));
        assertEquals("i-1", row.get(3));
        assertEquals("High CPU firing", row.get(4));
        assertEquals("value 0.95", row.get(5));
    }

    @Test
    void nullDetailBecomesEmptyString() throws Exception {
        FakeClickHouseWrites writes = new FakeClickHouseWrites();
        new EventWriter(writes).record(7L, "EC2_STATE", "i-1", "stopped", null, ts);
        assertEquals("", writes.calls.get(0).rows().get(0).get(5));
    }

    @Test
    void unknownKindIsRejectedBeforeAnyWrite() {
        FakeClickHouseWrites writes = new FakeClickHouseWrites();
        assertThrows(IllegalArgumentException.class,
                () -> new EventWriter(writes).record(7L, "NOT_A_KIND", "i-1", "t", "d", ts));
        assertTrue(writes.calls.isEmpty());
    }
}
