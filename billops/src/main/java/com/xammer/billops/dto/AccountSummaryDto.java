package com.xammer.billops.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public class AccountSummaryDto {
    private Long id;
    private String accountName;
    private String provider; // AWS, GCP, Azure
    private String accountIdentifier; // AWS Account ID, GCP Project ID, or Azure Subscription ID
    private String status;
    private BigDecimal currentMonthSpending;
    private BigDecimal lastMonthSpending;
    private LocalDate lastInvoiceDate;
    private String clientName;
    private Long clientId;

    public AccountSummaryDto() {
    }

    public AccountSummaryDto(Long id, String accountName, String provider, String accountIdentifier,
                            String status, BigDecimal currentMonthSpending, BigDecimal lastMonthSpending,
                            LocalDate lastInvoiceDate, String clientName, Long clientId) {
        this.id = id;
        this.accountName = accountName;
        this.provider = provider;
        this.accountIdentifier = accountIdentifier;
        this.status = status;
        this.currentMonthSpending = currentMonthSpending;
        this.lastMonthSpending = lastMonthSpending;
        this.lastInvoiceDate = lastInvoiceDate;
        this.clientName = clientName;
        this.clientId = clientId;
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getAccountName() {
        return accountName;
    }

    public void setAccountName(String accountName) {
        this.accountName = accountName;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getAccountIdentifier() {
        return accountIdentifier;
    }

    public void setAccountIdentifier(String accountIdentifier) {
        this.accountIdentifier = accountIdentifier;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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

    public LocalDate getLastInvoiceDate() {
        return lastInvoiceDate;
    }

    public void setLastInvoiceDate(LocalDate lastInvoiceDate) {
        this.lastInvoiceDate = lastInvoiceDate;
    }

    public String getClientName() {
        return clientName;
    }

    public void setClientName(String clientName) {
        this.clientName = clientName;
    }

    public Long getClientId() {
        return clientId;
    }

    public void setClientId(Long clientId) {
        this.clientId = clientId;
    }
}
