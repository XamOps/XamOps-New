package com.xammer.cloud.service;

import com.xammer.cloud.dto.AgentHealthDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Agent health classification and roster assembly.
 *
 * This is the single source of truth consumed by both the console and the agent-silence
 * alert emails, so the thresholds and the two-window merge are worth pinning down.
 */
class AgentHealthServiceTest {

    private static final long MIN = 60_000L;
    private static final long HOUR = 3_600_000L;
    private static final long DAY = 24 * HOUR;

    private OpenObserveService openObserve;
    private AgentHealthService service;

    @BeforeEach
    void setUp() {
        openObserve = mock(OpenObserveService.class);
        service = new AgentHealthService(openObserve);
    }

    // ── classify ──────────────────────────────────────────────────────────────

    @Test
    void classifiesRecentReportsAsHealthy() {
        long now = 1_000 * DAY;
        // Agents scrape every 30s, so a few missed intervals must not look like a fault.
        assertThat(AgentHealthService.classify(now, now)).isEqualTo(AgentHealthDto.Status.HEALTHY);
        assertThat(AgentHealthService.classify(now - 3 * MIN, now)).isEqualTo(AgentHealthDto.Status.HEALTHY);
    }

    @Test
    void classifiesTheMiddleBandAsDelayedNotOffline() {
        long now = 1_000 * DAY;
        assertThat(AgentHealthService.classify(now - 3 * MIN - 1, now)).isEqualTo(AgentHealthDto.Status.DELAYED);
        assertThat(AgentHealthService.classify(now - 15 * MIN, now)).isEqualTo(AgentHealthDto.Status.DELAYED);
    }

    @Test
    void classifiesProlongedSilenceAsOfflineThenGone() {
        long now = 1_000 * DAY;
        assertThat(AgentHealthService.classify(now - 15 * MIN - 1, now)).isEqualTo(AgentHealthDto.Status.OFFLINE);
        // The reported production case: hosts last seen about an hour ago.
        assertThat(AgentHealthService.classify(now - HOUR, now)).isEqualTo(AgentHealthDto.Status.OFFLINE);
        assertThat(AgentHealthService.classify(now - DAY, now)).isEqualTo(AgentHealthDto.Status.OFFLINE);
        assertThat(AgentHealthService.classify(now - DAY - 1, now)).isEqualTo(AgentHealthDto.Status.GONE);
    }

    @Test
    void treatsAnUnresolvedTimestampAsGone() {
        assertThat(AgentHealthService.classify(0, 1_000 * DAY)).isEqualTo(AgentHealthDto.Status.GONE);
    }

    // ── listAgents ────────────────────────────────────────────────────────────

    /** Builds a Prometheus-shaped range response for the given host → lastSeen pairs. */
    private Map<String, Object> matrix(Map<String, Long> lastSeenByHost) {
        List<Object> result = lastSeenByHost.entrySet().stream()
                .map(e -> (Object) Map.of(
                        "metric", Map.of("host_name", e.getKey()),
                        "values", List.of(List.of(e.getValue() / 1000.0, "1"))))
                .toList();
        return Map.of("data", Map.of("result", result));
    }

    @Test
    void keepsHostsFoundOnlyInTheWideRosterWindow() {
        long now = System.currentTimeMillis();

        // Wide roster knows two hosts; only one has reported in the last hour.
        when(openObserve.queryMetrics(anyLong(), any(), contains("system_cpu_utilization"),
                anyLong(), anyLong(), eq("1h")))
                .thenReturn(matrix(Map.of("live-host", now - MIN, "dead-host", now - 3 * DAY)));
        when(openObserve.queryMetrics(anyLong(), any(), contains("system_cpu_utilization"),
                anyLong(), anyLong(), eq("60s")))
                .thenReturn(matrix(Map.of("live-host", now - MIN)));

        List<AgentHealthDto> agents = service.listAgents(1L, "acct-1");

        // An agent that died days ago must remain visible rather than silently vanishing,
        // which is what the old single 6-hour lookback did.
        assertThat(agents).extracting(AgentHealthDto::getHostName)
                .containsExactlyInAnyOrder("live-host", "dead-host");
        assertThat(agents).filteredOn(a -> a.getHostName().equals("dead-host"))
                .extracting(AgentHealthDto::getStatus)
                .containsExactly(AgentHealthDto.Status.GONE);
        assertThat(agents).filteredOn(a -> a.getHostName().equals("live-host"))
                .extracting(AgentHealthDto::getStatus)
                .containsExactly(AgentHealthDto.Status.HEALTHY);
    }

    @Test
    void ordersOfflineAgentsAheadOfRemovedAndHealthyOnes() {
        long now = System.currentTimeMillis();
        Map<String, Long> hosts = Map.of(
                "healthy", now - MIN,
                "offline", now - 2 * HOUR,
                "gone", now - 5 * DAY);
        when(openObserve.queryMetrics(anyLong(), any(), any(), anyLong(), anyLong(), any()))
                .thenReturn(matrix(hosts));

        assertThat(service.listAgents(1L, "acct-1"))
                .extracting(AgentHealthDto::getHostName)
                .startsWith("offline");
    }

    @Test
    void flagsCollectorAliveWhenSelfTelemetryArrivesButHostMetricsAreStale() {
        long now = System.currentTimeMillis();

        when(openObserve.queryMetrics(anyLong(), any(), contains("system_cpu_utilization"),
                anyLong(), anyLong(), any()))
                .thenReturn(matrix(Map.of("broken-pipeline", now - 2 * HOUR)));
        // Collector is still exporting its own telemetry, so the process is up.
        when(openObserve.queryMetrics(anyLong(), any(), contains("otelcol_process_uptime"),
                anyLong(), anyLong(), any()))
                .thenReturn(matrix(Map.of("broken-pipeline", now - MIN)));

        List<AgentHealthDto> agents = service.listAgents(1L, "acct-1");

        // "Agent alive, pipeline broken" needs a config fix, not a restart — a materially
        // different action than a dead agent, so it must be distinguishable.
        assertThat(agents).singleElement()
                .satisfies(a -> {
                    assertThat(a.getStatus()).isEqualTo(AgentHealthDto.Status.OFFLINE);
                    assertThat(a.isCollectorAlive()).isTrue();
                });
    }

    @Test
    void doesNotClaimCollectorAliveForAHealthyAgent() {
        long now = System.currentTimeMillis();
        when(openObserve.queryMetrics(anyLong(), any(), any(), anyLong(), anyLong(), any()))
                .thenReturn(matrix(Map.of("fine", now - MIN)));

        assertThat(service.listAgents(1L, "acct-1")).singleElement()
                .extracting(AgentHealthDto::isCollectorAlive)
                .isEqualTo(false);
    }

    @Test
    void returnsEmptyRatherThanFailingWhenOpenObserveIsUnavailable() {
        when(openObserve.queryMetrics(anyLong(), any(), any(), anyLong(), anyLong(), any()))
                .thenThrow(new RuntimeException("O2 unreachable"));

        // Health is a diagnostic aid; failing to compute it must not break the page or the
        // scheduled alert scan that calls it.
        assertThat(service.listAgents(1L, "acct-1")).isEmpty();
    }

    @Test
    void scopesTheQueryToTheSelectedAccount() {
        when(openObserve.queryMetrics(anyLong(), any(), any(), anyLong(), anyLong(), any()))
                .thenReturn(Map.of());

        service.listAgents(1L, "605134457560");

        // Without the label filter, hosts from other accounts bleed into the roster.
        org.mockito.ArgumentCaptor<String> query = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(openObserve, org.mockito.Mockito.atLeastOnce())
                .queryMetrics(anyLong(), any(), query.capture(), anyLong(), anyLong(), any());
        assertThat(query.getAllValues())
                .allSatisfy(q -> assertThat(q).contains("cloud_account_id=\"605134457560\""));
    }
}
