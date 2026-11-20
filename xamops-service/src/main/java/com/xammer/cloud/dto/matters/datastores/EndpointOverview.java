package com.xammer.cloud.dto.matters.datastores;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The Endpoint drawer's header strip and Properties card (design 5.4 item 8). The header icon comes
 * from the list row.
 *
 * @param lastScanAt   epoch seconds.
 * @param lastAccessAt epoch seconds.
 * @param agentOnline  the status icon and the utilization rule.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record EndpointOverview(String serialNumber, Long lastScanAt, Long lastAccessAt, String severityLabel,
                               String agentStatusLabel, boolean agentOnline, List<String> users, String hostname,
                               String visibleIp, String macAddress, String deviceTypeLabel, String osVersion,
                               String architecture, String memory, Double cpuUsage, Double memoryUsage,
                               String agentVersion) {
}
