package com.xammer.cloud.dto.compai;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Roll-up for the SOC2 Program Overview tab, computed from the other DTOs. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OverviewDto {
    private String frameworkName;
    private int readinessPercent;
    private int controlsTotal;
    private int controlsPassing;
    private int controlsInProgress;
    private int controlsNotStarted;
    private int policiesTotal;
    private int policiesPublished;
    private int tasksTotal;
    private int tasksOpen;
    private int peopleTotal;
    private int vendorsTotal;
    private int risksTotal;
    private int cloudFindingsTotal;
    private int integrationsConnected;
    private String trustCenterUrl;
}
