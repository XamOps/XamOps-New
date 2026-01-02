package com.xammer.cloud.dto;

import lombok.Data;
import java.util.List;

@Data
public class CloudsitterPolicyDto {
    private Long id;
    private String name;
    private String type; // "Schedule"
    private String timeZone;
    private boolean notificationsEnabled;
    private String notificationEmail;

    // Represents the grid: Map of Day -> List of Hours [0..23] where instance is ON
    // Or a simple cron expression string
    private String scheduleConfig;
}
