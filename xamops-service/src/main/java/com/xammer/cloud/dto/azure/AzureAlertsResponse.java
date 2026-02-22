package com.xammer.cloud.dto.azure;

import java.util.List;

/**
 * Wrapper response for Azure Monitor alerts.
 */
public class AzureAlertsResponse {

    public enum Status {
        OK,               // 200 with 1+ alerts
        NO_DATA,          // 200 but 0 alerts (no active alerts in the subscription)
        PERMISSION_ERROR, // 403 — Monitoring Reader role required
        API_ERROR         // other HTTP error or exception
    }

    private Status status;
    private List<AzureMonitorAlert> alerts;

    public AzureAlertsResponse() {}

    public AzureAlertsResponse(Status status, List<AzureMonitorAlert> alerts) {
        this.status = status;
        this.alerts = alerts;
    }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public List<AzureMonitorAlert> getAlerts() { return alerts; }
    public void setAlerts(List<AzureMonitorAlert> alerts) { this.alerts = alerts; }
}
