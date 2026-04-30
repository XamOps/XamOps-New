package com.xammer.cloud.dto.sre;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SreStatsDto {
    private long totalInvestigations;
    private long activeInvestigations;   // PENDING + RUNNING
    private long completedToday;
    private long failedToday;
    private Long avgMttrSeconds;         // null if no completed investigations
}
