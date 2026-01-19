package com.xammer.billops.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public class AdminDashboardDto {
    private BigDecimal totalSpending;
    private Long totalAccounts;
    private Long awsAccountsCount;
    private Long gcpAccountsCount;
    private Long azureAccountsCount;
    private Long pendingInvoices;
    private Long finalizedInvoices;
    private Long openTickets;
    private Long totalClients;
    private Long lastUpdated;

    private List<AccountSummaryDto> topSpendingAccounts;
    private Map<String, BigDecimal> spendingByProvider;
    private Map<String, Long> accountsByStatus;
    private List<InvoiceDto> recentInvoices;
    private List<TicketDto> recentTickets;

    public AdminDashboardDto() {
    }

    // Getters and Setters
    public BigDecimal getTotalSpending() {
        return totalSpending;
    }

    public void setTotalSpending(BigDecimal totalSpending) {
        this.totalSpending = totalSpending;
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

    public Long getFinalizedInvoices() {
        return finalizedInvoices;
    }

    public void setFinalizedInvoices(Long finalizedInvoices) {
        this.finalizedInvoices = finalizedInvoices;
    }

    public Long getOpenTickets() {
        return openTickets;
    }

    public void setOpenTickets(Long openTickets) {
        this.openTickets = openTickets;
    }

    public Long getTotalClients() {
        return totalClients;
    }

    public void setTotalClients(Long totalClients) {
        this.totalClients = totalClients;
    }

    public Long getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(Long lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    public List<AccountSummaryDto> getTopSpendingAccounts() {
        return topSpendingAccounts;
    }

    public void setTopSpendingAccounts(List<AccountSummaryDto> topSpendingAccounts) {
        this.topSpendingAccounts = topSpendingAccounts;
    }

    public Map<String, BigDecimal> getSpendingByProvider() {
        return spendingByProvider;
    }

    public void setSpendingByProvider(Map<String, BigDecimal> spendingByProvider) {
        this.spendingByProvider = spendingByProvider;
    }

    public Map<String, Long> getAccountsByStatus() {
        return accountsByStatus;
    }

    public void setAccountsByStatus(Map<String, Long> accountsByStatus) {
        this.accountsByStatus = accountsByStatus;
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
}
