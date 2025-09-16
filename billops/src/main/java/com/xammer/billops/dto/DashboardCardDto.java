package com.xammer.billops.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DashboardCardDto {
    private BigDecimal currentMonthSpending;
    private BigDecimal lastMonthSpending;
    private BigDecimal forecastedSpending;
    private long ticketsRaised;
    private long creditRequests;
}