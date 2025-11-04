package com.xammer.cloud.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FinOpsReportScheduleDto {

    private Long id;
    private String cloudAccountId; // The provider account ID (e.g., AWS ID or GCP Project)
    private String accountName;
    private String email;
    private String frequency; // "DAILY", "WEEKLY", "MONTHLY"
    private boolean isActive;

    // Constructor for creating a new schedule
    public FinOpsReportScheduleDto(String cloudAccountId, String email, String frequency) {
        this.cloudAccountId = cloudAccountId;
        this.email = email;
        this.frequency = frequency;
    }
}