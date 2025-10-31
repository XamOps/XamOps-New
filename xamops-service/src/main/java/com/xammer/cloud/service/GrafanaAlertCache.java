package com.xammer.cloud.service;

import com.xammer.cloud.dto.AlertDto;
import com.xammer.cloud.dto.GrafanaWebhookDto;
import org.springframework.stereotype.Service;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@Service
public class GrafanaAlertCache {
    // Use a thread-safe list to store alerts in memory
    private final List<AlertDto> cachedAlerts = new CopyOnWriteArrayList<>();

    /**
     * Called by the webhook to add new alerts.
     */
    public void processIncomingAlerts(GrafanaWebhookDto payload) {
        if (payload.getAlerts() == null) {
            return;
        }
        // Clear old alerts and add new ones (simple PoC logic)
        // A real app would update existing alerts.
        cachedAlerts.clear();
        List<AlertDto> newAlerts = payload.getAlerts().stream()
                .map(this::transformToAlertDto)
                .collect(Collectors.toList());
        cachedAlerts.addAll(newAlerts);
    }

    /**
     * Called by the UI to fetch alerts.
     */
    public List<AlertDto> getCachedAlerts() {
        // Return an immutable copy
        return Collections.unmodifiableList(cachedAlerts);
    }

    /**
     * Simple transformer. You can make this more detailed.
     */
    private AlertDto transformToAlertDto(GrafanaWebhookDto.GrafanaAlert gAlert) {
        AlertDto dto = new AlertDto();
        dto.setId(gAlert.getLabels().getOrDefault("instance", "N/A"));
        dto.setName(gAlert.getLabels().getOrDefault("alertname", "Unnamed Alert"));
        dto.setDescription(gAlert.getAnnotations().getOrDefault("description", "No description."));
        dto.setStatus(gAlert.getStatus());
        dto.setService("Grafana");
        dto.setRegion(gAlert.getLabels().getOrDefault("region", "default"));
        // We can re-use the AlertDto from your code
        return dto;
    }
}
