package com.xammer.cloud.dto.azure;

public class AzureAccountRequestDto {

    private String accountName;
    private String tenantId;
    private String subscriptionId;
    private String clientId;
    private String clientSecret;
    private String principalId;
    
    // --- ADD THESE TWO LINES ---
    private String billingStorageAccountId;
    private String billingResourceGroup;

    // Getters
    public String getAccountName() { return accountName; }
    public String getTenantId() { return tenantId; }
    public String getSubscriptionId() { return subscriptionId; }
    public String getClientId() { return clientId; }
    public String getClientSecret() { return clientSecret; }
    public String getPrincipalId() { return principalId; }
    
    // --- ADD GETTERS FOR NEW FIELDS ---
    public String getBillingStorageAccountId() { return billingStorageAccountId; }
    public String getBillingResourceGroup() { return billingResourceGroup; }

    // Setters
    public void setAccountName(String accountName) { this.accountName = accountName; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public void setSubscriptionId(String subscriptionId) { this.subscriptionId = subscriptionId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
    public void setPrincipalId(String principalId) { this.principalId = principalId; }

    // --- ADD SETTERS FOR NEW FIELDS ---
    public void setBillingStorageAccountId(String billingStorageAccountId) { this.billingStorageAccountId = billingStorageAccountId; }
    public void setBillingResourceGroup(String billingResourceGroup) { this.billingResourceGroup = billingResourceGroup; }
}