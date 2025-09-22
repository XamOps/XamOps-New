package com.xammer.cloud.service.azure;

import com.azure.core.credential.TokenCredential;
import com.azure.core.management.AzureEnvironment; // Import this class
import com.azure.core.management.profile.AzureProfile;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.azure.resourcemanager.AzureResourceManager;
import com.xammer.cloud.domain.CloudAccount;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AzureClientProvider {

    private final Map<String, AzureResourceManager> clientCache = new ConcurrentHashMap<>();

    public AzureResourceManager getAzureClient(CloudAccount account) {
        String subscriptionId = account.getAzureSubscriptionId();
        if (clientCache.containsKey(subscriptionId)) {
            return clientCache.get(subscriptionId);
        }

        TokenCredential credential = new ClientSecretCredentialBuilder()
                .clientId(account.getAzureClientId())
                .clientSecret(account.getAzureClientSecret())
                .tenantId(account.getAzureTenantId())
                .build();

        // Use the correct AzureProfile constructor with tenantId, subscriptionId, and environment
        AzureProfile profile = new AzureProfile(account.getAzureTenantId(), subscriptionId, AzureEnvironment.AZURE);

        AzureResourceManager azureClient = AzureResourceManager
                .configure()
                .authenticate(credential, profile)
                .withSubscription(subscriptionId);

        clientCache.put(subscriptionId, azureClient);
        return azureClient;
    }
}