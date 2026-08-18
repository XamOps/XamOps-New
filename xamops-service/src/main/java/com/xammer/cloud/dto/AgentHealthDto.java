package com.xammer.cloud.dto;

/**
 * Reporting health of a single agent (host), derived from how recently it last shipped
 * metrics.
 *
 * Classification lives on the server so the UI, the alerting scheduler, and any future
 * consumer agree. It was briefly duplicated in the frontend, which meant the thresholds
 * could drift apart and the console could disagree with the alert you were emailed.
 */
public class AgentHealthDto {

    public enum Status {
        /** Reporting normally. */
        HEALTHY,
        /** Late, but recent enough to be a blip rather than an outage. */
        DELAYED,
        /** Silent long enough to be a real outage. */
        OFFLINE,
        /** Silent for over a day — host terminated or agent uninstalled. */
        GONE
    }

    private String hostName;
    private long lastSeenMs;
    private Status status;
    private long ageMs;
    /**
     * True when the collector's own telemetry is arriving even though host metrics are not —
     * the agent process is alive and its pipeline is broken, which is a different problem
     * with a different fix than a dead agent.
     */
    private boolean collectorAlive;

    public AgentHealthDto() {
    }

    public AgentHealthDto(String hostName, long lastSeenMs, Status status, long ageMs,
                          boolean collectorAlive) {
        this.hostName = hostName;
        this.lastSeenMs = lastSeenMs;
        this.status = status;
        this.ageMs = ageMs;
        this.collectorAlive = collectorAlive;
    }

    public String getHostName() { return hostName; }
    public void setHostName(String hostName) { this.hostName = hostName; }

    public long getLastSeenMs() { return lastSeenMs; }
    public void setLastSeenMs(long lastSeenMs) { this.lastSeenMs = lastSeenMs; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public long getAgeMs() { return ageMs; }
    public void setAgeMs(long ageMs) { this.ageMs = ageMs; }

    public boolean isCollectorAlive() { return collectorAlive; }
    public void setCollectorAlive(boolean collectorAlive) { this.collectorAlive = collectorAlive; }
}
