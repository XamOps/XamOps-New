package com.xammer.cloud.service;

import com.xammer.cloud.dto.AgentHealthDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Derives per-agent reporting health from metrics already in OpenObserve.
 *
 * Agents ship on a 30s collection_interval, so "time since last sample" is a reliable
 * liveness signal and needs no new agent-side plumbing. Thresholds are multiples of that
 * interval so a couple of dropped scrapes don't read as an outage.
 *
 * Two lookback windows are used deliberately:
 *  - a wide one to establish which hosts exist at all, so an agent that stopped days ago is
 *    still listed as removed instead of silently vanishing from the UI
 *  - a narrow, fine-grained one to pin down when each host last reported, because the wide
 *    window's step is far too coarse to tell "reporting now" from "stopped 45 minutes ago"
 */
@Service
public class AgentHealthService {

    private static final Logger logger = LoggerFactory.getLogger(AgentHealthService.class);

    static final long HEALTHY_MAX_MS = 3 * 60_000L;         // 3 min  = 6 collection intervals
    static final long DELAYED_MAX_MS = 15 * 60_000L;        // 15 min = 30 intervals
    static final long OFFLINE_MAX_MS = 24 * 3_600_000L;     // 24 h
    static final long ROSTER_LOOKBACK_MS = 7 * 24 * 3_600_000L;

    private final OpenObserveService openObserveService;

    public AgentHealthService(OpenObserveService openObserveService) {
        this.openObserveService = openObserveService;
    }

    public static AgentHealthDto.Status classify(long lastSeenMs, long now) {
        if (lastSeenMs <= 0) return AgentHealthDto.Status.GONE;
        long age = now - lastSeenMs;
        if (age <= HEALTHY_MAX_MS) return AgentHealthDto.Status.HEALTHY;
        if (age <= DELAYED_MAX_MS) return AgentHealthDto.Status.DELAYED;
        if (age <= OFFLINE_MAX_MS) return AgentHealthDto.Status.OFFLINE;
        return AgentHealthDto.Status.GONE;
    }

    /** Agents known to this account (or the whole client when no account is given). */
    public List<AgentHealthDto> listAgents(Long clientId, String providerAccountId) {
        long now = System.currentTimeMillis();

        String filter = (providerAccountId == null || providerAccountId.isBlank())
                ? ""
                : "{cloud_account_id=\"" + providerAccountId + "\"}";
        String hostQuery = "group by (host_name) (system_cpu_utilization" + filter + ")";

        Map<String, Long> roster = lastSeenByHost(
                clientId, providerAccountId, hostQuery, now - ROSTER_LOOKBACK_MS, now, "1h");
        Map<String, Long> recent = lastSeenByHost(
                clientId, providerAccountId, hostQuery, now - 3_600_000L, now, "60s");

        // Collector self-telemetry. Present means the agent process is alive and exporting,
        // so stale host metrics point at the scrape pipeline rather than a dead agent.
        // Requires an agent built with the self-telemetry scrape enabled; absent for older
        // installs, in which case we simply can't make the distinction.
        Map<String, Long> collector = lastSeenByHost(
                clientId, providerAccountId,
                "group by (host_name) (otelcol_process_uptime" + filter + ")",
                now - 3_600_000L, now, "60s");

        Map<String, Long> merged = new HashMap<>(roster);
        recent.forEach((host, ts) -> merged.merge(host, ts, Math::max));

        List<AgentHealthDto> agents = new ArrayList<>();
        merged.forEach((host, lastSeen) -> {
            AgentHealthDto.Status status = classify(lastSeen, now);
            boolean collectorAlive = status != AgentHealthDto.Status.HEALTHY
                    && classify(collector.getOrDefault(host, 0L), now) == AgentHealthDto.Status.HEALTHY;
            agents.add(new AgentHealthDto(host, lastSeen, status,
                    lastSeen > 0 ? now - lastSeen : Long.MAX_VALUE, collectorAlive));
        });

        // Most urgent first so a truncated list still shows what matters.
        agents.sort(Comparator
                .comparingInt((AgentHealthDto a) -> severity(a.getStatus()))
                .thenComparing(AgentHealthDto::getLastSeenMs, Comparator.reverseOrder()));
        return agents;
    }

    private static int severity(AgentHealthDto.Status status) {
        switch (status) {
            case OFFLINE: return 0;
            case DELAYED: return 1;
            case GONE:    return 2;
            default:      return 3;
        }
    }

    /**
     * Runs a range query and reduces each series to the timestamp of its newest sample.
     *
     * Returns an empty map rather than throwing: agent health is a diagnostic aid, and a
     * failure to compute it must not take down the page or the alert scan that called it.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Long> lastSeenByHost(Long clientId, String providerAccountId,
                                             String query, long start, long end, String step) {
        Map<String, Long> lastSeen = new HashMap<>();
        try {
            Map<String, Object> response =
                    openObserveService.queryMetrics(clientId, providerAccountId, query, start, end, step);
            Object dataObj = response == null ? null : response.get("data");
            if (!(dataObj instanceof Map)) return lastSeen;

            Object resultObj = ((Map<String, Object>) dataObj).get("result");
            if (!(resultObj instanceof List)) return lastSeen;

            for (Object seriesObj : (List<Object>) resultObj) {
                if (!(seriesObj instanceof Map)) continue;
                Map<String, Object> series = (Map<String, Object>) seriesObj;

                Object metricObj = series.get("metric");
                if (!(metricObj instanceof Map)) continue;
                Object host = ((Map<String, Object>) metricObj).get("host_name");
                if (host == null) continue;

                Object valuesObj = series.get("values");
                if (!(valuesObj instanceof List) || ((List<?>) valuesObj).isEmpty()) continue;
                List<Object> values = (List<Object>) valuesObj;

                Object lastPair = values.get(values.size() - 1);
                if (!(lastPair instanceof List) || ((List<?>) lastPair).isEmpty()) continue;

                // Prometheus range results are [unixSeconds, "value"] pairs.
                long tsMs = (long) (Double.parseDouble(String.valueOf(((List<?>) lastPair).get(0))) * 1000);
                lastSeen.merge(String.valueOf(host), tsMs, Math::max);
            }
        } catch (Exception e) {
            logger.warn("Agent health query failed (client={}, account={}): {}",
                    clientId, providerAccountId, e.getMessage());
        }
        return lastSeen;
    }
}
