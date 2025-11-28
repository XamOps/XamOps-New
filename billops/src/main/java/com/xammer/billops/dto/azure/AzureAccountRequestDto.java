package com.xammer.billops.dto.azure;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AzureAccountRequestDto {
    private String accountName;
    private String tenantId;
    private String subscriptionId;
    private String clientId;
    private String clientSecret;
    private String access;
    private String principalId;
    
    // Fields for billing export configuration
    private String billingResourceGroup;
    private String billingStorageAccountId;
}