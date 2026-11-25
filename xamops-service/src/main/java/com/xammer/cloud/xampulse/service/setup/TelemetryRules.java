package com.xammer.cloud.xampulse.service.setup;

import com.xammer.cloud.xampulse.domain.XpAgent;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Pure rules for "has data landed" so the watcher and the checklist agree. */
final class TelemetryRules {

    private TelemetryRules() {}

    static boolean reporting(XpAgent a, Instant now) {
        return "ACTIVE".equals(a.getState()) && a.getLastSeenAt() != null
                && Duration.between(a.getLastSeenAt(), now).compareTo(XamPulseStackWatcher.SILENT_AFTER) <= 0;
    }

    static boolean metricsLanded(String region, List<XpAgent> agents, TelemetryProbe.Landed landed) {
        return landed.available() && agents.stream().anyMatch(a -> region.equals(a.getRegion()) && landed.metricInstances().contains(a.getInstanceId()));
    }

    static boolean logsLanded(String region, TelemetryProbe.Landed landed) {
        return landed.available() && landed.logRegions().contains(region);
    }

    /**
     * True when at least one agent is reporting and every region that has a reporting agent has seen metrics or logs.
     * One healthy region therefore cannot mask a region whose agents heartbeat but ship nothing.
     */
    static boolean dataLandedEverywhere(List<XpAgent> agents, TelemetryProbe.Landed landed, Instant now) {
        if (!landed.available()) {
            return false;
        }
        List<String> regions = agents.stream().filter(a -> reporting(a, now)).map(XpAgent::getRegion).distinct().toList();
        return !regions.isEmpty() && regions.stream().allMatch(r -> metricsLanded(r, agents, landed) || logsLanded(r, landed));
    }
}
