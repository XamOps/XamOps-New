package com.xammer.cloud.service.azure;

import com.azure.core.credential.TokenCredential;
import com.azure.core.management.AzureEnvironment;
import com.azure.core.management.profile.AzureProfile;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.azure.resourcemanager.AzureResourceManager;
import com.xammer.cloud.domain.CloudAccount;
import org.springframework.stereotype.Service;

@Service
public class AzureClientProvider {

    public AzureResourceManager getAzureClient(CloudAccount account) {
        TokenCredential credential = new ClientSecretCredentialBuilder()
                .clientId(account.getAzureClientId())
                .clientSecret(account.getAzureClientSecret())
                .tenantId(account.getAzureTenantId())
                .build();

        AzureProfile profile = new AzureProfile(account.getAzureTenantId(), account.getAzureSubscriptionId(), AzureEnvironment.AZURE);

        return AzureResourceManager.authenticate(credential, profile).withDefaultSubscription();
    }

    public boolean verifyCredentials(String tenantId, String subscriptionId, String clientId, String clientSecret) {
        try {
            TokenCredential credential = new ClientSecretCredentialBuilder()
                    .clientId(clientId)
                    .clientSecret(clientSecret)
                    .tenantId(tenantId)
                    .build();
            AzureProfile profile = new AzureProfile(tenantId, subscriptionId, AzureEnvironment.AZURE);
            AzureResourceManager.authenticate(credential, profile).subscriptions().list();
            return true;
        } catch (Exception e) {
            // Log the exception
            return false;
        }
    }
}