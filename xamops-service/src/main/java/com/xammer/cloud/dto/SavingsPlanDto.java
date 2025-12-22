package com.xammer.cloud.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SavingsPlanDto {
    private String savingsPlanId;
    private String savingsPlanArn;
    private String description;
    private String state;
    private String type;
    private String paymentOption;
    private String start;
    private String end;
    private String region;
    private String commitment; // e.g., "$0.10/hour"
    private String upfrontCost;
    private String currency;
}