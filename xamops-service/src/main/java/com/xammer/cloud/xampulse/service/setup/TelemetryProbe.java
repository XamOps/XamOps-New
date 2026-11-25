package com.xammer.cloud.xampulse.service.setup;

import java.util.Set;

/** Answers "has any telemetry actually landed for this account?" so CONNECTED means data, not just a heartbeat. */
public interface TelemetryProbe {

    /**
     * @param logRegions      regions with at least one log row in the window
     * @param metricInstances instances with at least one metric point (other than the heartbeat) in the window
     * @param available       false when the store could not be read; callers must then treat the answer as unknown
     */
    record Landed(Set<String> logRegions, Set<String> metricInstances, boolean available) {
        public static Landed unavailable() {
            return new Landed(Set.of(), Set.of(), false);
        }
    }

    Landed landed(long tenantId);
}
