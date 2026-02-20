package com.xammer.billops.dto.azure;

import lombok.Data;

@Data
public class AzureAccountRequestDto {
    private String azureSubscriptionId;
    private String azureTenantId;
    private String azureClientId;
    private String azureClientSecret;
    private String billingResourceGroup;
    private String billingStorageAccountId;

    public String getAzureSubscriptionId() { return azureSubscriptionId; }
    public void setAzureSubscriptionId(String azureSubscriptionId) { this.azureSubscriptionId = azureSubscriptionId; }
    public String getAzureTenantId() { return azureTenantId; }
    public void setAzureTenantId(String azureTenantId) { this.azureTenantId = azureTenantId; }
    public String getAzureClientId() { return azureClientId; }
    public void setAzureClientId(String azureClientId) { this.azureClientId = azureClientId; }
    public String getAzureClientSecret() { return azureClientSecret; }
    public void setAzureClientSecret(String azureClientSecret) { this.azureClientSecret = azureClientSecret; }
    public String getBillingResourceGroup() { return billingResourceGroup; }
    public void setBillingResourceGroup(String billingResourceGroup) { this.billingResourceGroup = billingResourceGroup; }
    public String getBillingStorageAccountId() { return billingStorageAccountId; }
    public void setBillingStorageAccountId(String billingStorageAccountId) { this.billingStorageAccountId = billingStorageAccountId; }
}