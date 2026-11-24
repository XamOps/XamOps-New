package com.xammer.cloud.xampulse.service.query;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HostMemoryTest {

    private static Map<String, Object> row(String instance, String metric, String labels, double v) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("instance_id", instance);
        r.put("metric", metric);
        r.put("labels_key", labels);
        r.put("v", v);
        return r;
    }

    @Test
    void totalIsBytesUsedDividedByTheUsedShareOtherStatesIgnored() {
        ClickHouseExecutor executor = mock(ClickHouseExecutor.class);
        when(executor.queryEach(any(), any())).thenReturn(List.of(new ClickHouseExecutor.TenantRows(7L, List.of(
                row("i-a", "system.memory.usage", "state=used", 6.0e9),
                row("i-a", "system.memory.utilization", "state=used", 0.75),
                row("i-a", "system.memory.usage", "state=free", 2.0e9),
                row("i-b", "system.memory.usage", "state=used", 1.0e9))))); // no share reported: unknown, not guessed
        QueryScope scope = new QueryScope(List.of(7L), Instant.EPOCH, Instant.EPOCH.plusSeconds(60), List.of(), List.of(), List.of(), List.of());
        Map<String, Double> t = HostMemory.totals(executor, new TenantScopedQueryBuilder(), scope, 0);
        assertEquals(8.0e9, t.get("7/i-a"), 1);
        assertNull(t.get("7/i-b"));
    }

    @Test
    void aLimitAtTheHostsMemoryMeansNoLimitWasSet() {
        double host = 7.59 * 1024 * 1024 * 1024;
        assertTrue(HostMemory.isHostLimit(host, host));
        assertTrue(HostMemory.isHostLimit(host * 0.98, host));
        assertFalse(HostMemory.isHostLimit(2.0 * 1024 * 1024 * 1024, host), "a real 2 GB limit");
        assertNull(HostMemory.isHostLimit(null, host));
        assertNull(HostMemory.isHostLimit(host, null));
    }
}
