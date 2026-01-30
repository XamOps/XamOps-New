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
    private String frequency; // "DAILY", "WEEKLY", "MONTHLY", "CUSTOM"
    private boolean isActive;

    // Custom time fields (used when frequency is CUSTOM)
    private Integer customHour; // 0-23
    private Integer customMinute; // 0-59
    private Integer customDayOfWeek; // 1-7 (1=Monday, 7=Sunday) for weekly
    private Integer customDayOfMonth; // 1-31 for monthly
    private String customFrequencyType; // "DAILY", "WEEKLY", "MONTHLY" when frequency is CUSTOM

    // Constructor for creating a new schedule
    public FinOpsReportScheduleDto(String cloudAccountId, String email, String frequency) {
        this.cloudAccountId = cloudAccountId;
        this.email = email;
        this.frequency = frequency;
    }
}