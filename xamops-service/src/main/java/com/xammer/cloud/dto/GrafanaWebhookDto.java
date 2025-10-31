package com.xammer.cloud.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class GrafanaWebhookDto {
    // We only care about the list of alerts
    private List<GrafanaAlert> alerts;

    @Data
    public static class GrafanaAlert {
        private String status; // e.g., "firing", "resolved"
        private Map<String, String> labels; // e.g., "alertname", "instance"
        private Map<String, String> annotations; // e.g., "description", "summary"
    }
}
