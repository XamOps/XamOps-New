package com.xammer.cloud.xampulse.service.query;

import com.xammer.cloud.xampulse.repository.XpAgentRepository;
import com.xammer.cloud.xampulse.service.alert.IncidentService;
import com.xammer.cloud.xampulse.service.insight.HealthScoreService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The host list's network columns: latest bytes/s per host and direction from the counter-rate series. */
class HostListNetworkRatesTest {

    private static Map<String, Object> row(String instance, String dir, long t, Double v) {
        java.util.HashMap<String, Object> r = new java.util.HashMap<>();
        r.put("g0", instance);
        r.put("g1", dir);
        r.put("t", t);
        r.put("v", v);
        return r;
    }

    @Test
    void latestPointPerHostAndDirectionMissingDirectionStaysNoData() {
        ClickHouseExecutor executor = mock(ClickHouseExecutor.class);
        when(executor.queryEach(any(), any())).thenReturn(List.of(new ClickHouseExecutor.TenantRows(7L, List.of(
                row("i-a", "receive", 100, 10.0), row("i-a", "receive", 160, 30.0), row("i-a", "receive", 130, 99.0),
                row("i-a", "transmit", 160, 5.0),
                row("i-b", "transmit", 160, -3.0),      // a negative rate never reaches the list
                row("i-b", "sideways", 160, 1.0),       // an unknown direction is ignored
                row("i-c", "receive", 160, null)))));   // no value: host stays absent
        HostQueryService hosts = new HostQueryService(mock(QueryScopeFactory.class), executor, mock(XpAgentRepository.class),
                new HealthScoreService(), mock(IncidentService.class), mock(LogQueryService.class), mock(EventQueryService.class));
        QueryScope scope = new QueryScope(List.of(7L), Instant.parse("2026-11-23T06:00:00Z"), Instant.parse("2026-11-23T06:15:00Z"),
                List.of(), List.of(), List.of(), List.of());

        Map<String, double[]> m = hosts.networkRates(scope).get(7L);

        assertArrayEquals(new double[] {30.0, 5.0}, m.get("i-a"), 1e-9, "the latest bucket wins, not the largest");
        assertTrue(Double.isNaN(m.get("i-b")[0]), "no receive rate yet means no data, not 0 B/s");
        assertEquals(0.0, m.get("i-b")[1], 1e-9);
        assertNull(m.get("i-c"));
    }
}
