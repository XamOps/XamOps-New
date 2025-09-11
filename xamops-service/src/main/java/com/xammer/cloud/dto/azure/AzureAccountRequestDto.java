package com.xammer.cloud.dto.azure;

import lombok.Getter;

@Getter
public class AzureAccountRequestDto {

    // Getters and Setters
    private String accountName;
    private String tenantId;
    private String subscriptionId;
    private String clientId;
    private String clientSecret;
    private String azureCredentialsJson;

    public void setAccountName(String accountName) {
        this.accountName = accountName;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public void setSubscriptionId(String subscriptionId) {
        this.subscriptionId = subscriptionId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }
    public String getAzureCredentialsJson() {
        return azureCredentialsJson;
    }

    public void setAzureCredentialsJson(String azureCredentialsJson) {
        this.azureCredentialsJson = azureCredentialsJson;
    }
}