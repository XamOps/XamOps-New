package com.xammer.cloud.dto.azure;

public class AzureAccountRequestDto {

    private String accountName;
    private String tenantId;
    private String subscriptionId;
    private String clientId;
    private String clientSecret;
    private String principalId;
    
    // Fields from original file
    private String billingStorageAccountId;
    private String billingResourceGroup;

    // --- Fix for access_type error ---
    private String access;

    // Getters
    public String getAccountName() { return accountName; }
    public String getTenantId() { return tenantId; }
    public String getSubscriptionId() { return subscriptionId; }
    public String getClientId() { return clientId; }
    public String getClientSecret() { return clientSecret; }
    public String getPrincipalId() { return principalId; }
    
    // Getters for other fields
    public String getBillingStorageAccountId() { return billingStorageAccountId; }
    public String getBillingResourceGroup() { return billingResourceGroup; }

    // --- Getter for access field (replaces stub) ---
    public String getAccess() { return access; }

    // Setters
    public void setAccountName(String accountName) { this.accountName = accountName; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public void setSubscriptionId(String subscriptionId) { this.subscriptionId = subscriptionId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
    public void setPrincipalId(String principalId) { this.principalId = principalId; }

    // Setters for other fields
    public void setBillingStorageAccountId(String billingStorageAccountId) { this.billingStorageAccountId = billingStorageAccountId; }
    public void setBillingResourceGroup(String billingResourceGroup) { this.billingResourceGroup = billingResourceGroup; }

    // --- Setter for access field ---
    public void setAccess(String access) { this.access = access; }
}