package com.xammer.cloud.dto.azure;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AzureBudgetDto {
    private String budgetName;
    private double budgetLimit;
    private String budgetUnit;
    private double actualSpend;
    private double forecastedSpend;
}
