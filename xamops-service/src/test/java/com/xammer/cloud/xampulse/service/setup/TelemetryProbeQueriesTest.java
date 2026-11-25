package com.xammer.cloud.xampulse.service.setup;

import com.xammer.cloud.xampulse.service.query.QueryScope;
import com.xammer.cloud.xampulse.service.query.ScopedSql;
import com.xammer.cloud.xampulse.service.query.TenantScopedQueryBuilder;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TelemetryProbeQueriesTest {

    private final TenantScopedQueryBuilder b = new TenantScopedQueryBuilder();
    private final QueryScope scope = QueryScope.of(List.of(7L), Instant.parse("2026-11-02T00:00:00Z"), Instant.parse("2026-11-02T06:00:00Z"));

    @Test
    void landedQueriesAreSingleTenantAndFullyBound() {
        for (ScopedSql q : List.of(b.landedLogRegions(scope), b.landedMetricInstances(scope))) {
            assertTrue(q.sql().toLowerCase().contains("where tenant_id in (?"), q.sql());
            assertEquals(7L, q.params().get(0));
            assertEquals(q.sql().chars().filter(c -> c == '?').count(), q.params().size());
            assertFalse(q.sql().contains("7"), "tenant must be bound, not concatenated");
        }
        assertTrue(b.landedMetricInstances(scope).sql().contains("xp_metric_points"));
        assertTrue(b.landedLogRegions(scope).sql().contains("GROUP BY g0"));
    }
}
