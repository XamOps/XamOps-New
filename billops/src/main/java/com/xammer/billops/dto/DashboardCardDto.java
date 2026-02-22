package com.xammer.billops.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DashboardCardDto {
    private BigDecimal currentMonthCost;
    private BigDecimal previousMonthCost;
    private BigDecimal forecastedCost;
    private long activeAccounts;
    private long totalTickets;

    // public DashboardCardDto() {
    // }

    // public DashboardCardDto(BigDecimal currentMonthCost, BigDecimal
    // previousMonthCost, BigDecimal forecastedCost, long activeAccounts, long
    // totalTickets) {
    // this.currentMonthCost = currentMonthCost;
    // this.previousMonthCost = previousMonthCost;
    // this.forecastedCost = forecastedCost;
    // this.activeAccounts = activeAccounts;
    // this.totalTickets = totalTickets;
    // }

    public BigDecimal getCurrentMonthCost() {
        return currentMonthCost;
    }

    public void setCurrentMonthCost(BigDecimal currentMonthCost) {
        this.currentMonthCost = currentMonthCost;
    }

    public BigDecimal getPreviousMonthCost() {
        return previousMonthCost;
    }

    public void setPreviousMonthCost(BigDecimal previousMonthCost) {
        this.previousMonthCost = previousMonthCost;
    }

    public BigDecimal getForecastedCost() {
        return forecastedCost;
    }

    public void setForecastedCost(BigDecimal forecastedCost) {
        this.forecastedCost = forecastedCost;
    }

    public long getActiveAccounts() {
        return activeAccounts;
    }

    public void setActiveAccounts(long activeAccounts) {
        this.activeAccounts = activeAccounts;
    }

    public long getTotalTickets() {
        return totalTickets;
    }

    public void setTotalTickets(long totalTickets) {
        this.totalTickets = totalTickets;
    }
}