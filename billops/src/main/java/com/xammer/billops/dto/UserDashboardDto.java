package com.xammer.billops.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public class UserDashboardDto {
    private Long userId;
    private String username;
    private String clientName;
    private Long lastUpdated;

    private BigDecimal totalSpending;
    private BigDecimal currentMonthSpending;
    private BigDecimal lastMonthSpending;

    private Long totalAccounts;
    private Long awsAccountsCount;
    private Long gcpAccountsCount;
    private Long azureAccountsCount;

    private Long pendingInvoices;
    private Long openTickets;

    private List<AccountSummaryDto> accounts;
    private Map<String, BigDecimal> spendingByProvider;
    private List<CostHistory> spendingTrend;
    private List<InvoiceDto> recentInvoices;
    private List<TicketDto> recentTickets;

    public UserDashboardDto() {
    }

    // Getters and Setters
    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getClientName() {
        return clientName;
    }

    public void setClientName(String clientName) {
        this.clientName = clientName;
    }

    public Long getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(Long lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    public BigDecimal getTotalSpending() {
        return totalSpending;
    }

    public void setTotalSpending(BigDecimal totalSpending) {
        this.totalSpending = totalSpending;
    }

    public BigDecimal getCurrentMonthSpending() {
        return currentMonthSpending;
    }

    public void setCurrentMonthSpending(BigDecimal currentMonthSpending) {
        this.currentMonthSpending = currentMonthSpending;
    }

    public BigDecimal getLastMonthSpending() {
        return lastMonthSpending;
    }

    public void setLastMonthSpending(BigDecimal lastMonthSpending) {
        this.lastMonthSpending = lastMonthSpending;
    }

    public Long getTotalAccounts() {
        return totalAccounts;
    }

    public void setTotalAccounts(Long totalAccounts) {
        this.totalAccounts = totalAccounts;
    }

    public Long getAwsAccountsCount() {
        return awsAccountsCount;
    }

    public void setAwsAccountsCount(Long awsAccountsCount) {
        this.awsAccountsCount = awsAccountsCount;
    }

    public Long getGcpAccountsCount() {
        return gcpAccountsCount;
    }

    public void setGcpAccountsCount(Long gcpAccountsCount) {
        this.gcpAccountsCount = gcpAccountsCount;
    }

    public Long getAzureAccountsCount() {
        return azureAccountsCount;
    }

    public void setAzureAccountsCount(Long azureAccountsCount) {
        this.azureAccountsCount = azureAccountsCount;
    }

    public Long getPendingInvoices() {
        return pendingInvoices;
    }

    public void setPendingInvoices(Long pendingInvoices) {
        this.pendingInvoices = pendingInvoices;
    }

    public Long getOpenTickets() {
        return openTickets;
    }

    public void setOpenTickets(Long openTickets) {
        this.openTickets = openTickets;
    }

    public List<AccountSummaryDto> getAccounts() {
        return accounts;
    }

    public void setAccounts(List<AccountSummaryDto> accounts) {
        this.accounts = accounts;
    }

    public Map<String, BigDecimal> getSpendingByProvider() {
        return spendingByProvider;
    }

    public void setSpendingByProvider(Map<String, BigDecimal> spendingByProvider) {
        this.spendingByProvider = spendingByProvider;
    }

    public List<CostHistory> getSpendingTrend() {
        return spendingTrend;
    }

    public void setSpendingTrend(List<CostHistory> spendingTrend) {
        this.spendingTrend = spendingTrend;
    }

    public List<InvoiceDto> getRecentInvoices() {
        return recentInvoices;
    }

    public void setRecentInvoices(List<InvoiceDto> recentInvoices) {
        this.recentInvoices = recentInvoices;
    }

    public List<TicketDto> getRecentTickets() {
        return recentTickets;
    }

    public void setRecentTickets(List<TicketDto> recentTickets) {
        this.recentTickets = recentTickets;
    }

    // Inner class for cost history
    public static class CostHistory {
        private String date;
        private double cost;

        public CostHistory() {
        }

        public CostHistory(String date, double cost) {
            this.date = date;
            this.cost = cost;
        }

        public String getDate() {
            return date;
        }

        public void setDate(String date) {
            this.date = date;
        }

        public double getCost() {
            return cost;
        }

        public void setCost(double cost) {
            this.cost = cost;
        }
    }
}
