package com.xammer.cloud.service.azure;

import com.azure.core.credential.TokenCredential;
import com.azure.core.management.AzureEnvironment;
import com.azure.core.management.profile.AzureProfile;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.azure.resourcemanager.AzureResourceManager;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AzureClientProvider {

    private final Map<String, AzureResourceManager> clientCache = new ConcurrentHashMap<>();
    private final CloudAccountRepository cloudAccountRepository;

    public AzureClientProvider(CloudAccountRepository cloudAccountRepository) {
        this.cloudAccountRepository = cloudAccountRepository;
    }

    public AzureResourceManager getAzureClient(String subscriptionId) {
        return clientCache.computeIfAbsent(subscriptionId, id -> {
            // This now correctly finds the account by the Subscription ID string
            CloudAccount account = cloudAccountRepository.findByAzureSubscriptionId(id)
                    .orElseThrow(() -> new IllegalArgumentException("Azure account not found for Subscription ID: " + id));

            AzureProfile profile = new AzureProfile(account.getAzureTenantId(), account.getAzureSubscriptionId(), AzureEnvironment.AZURE);
            TokenCredential credential = buildCredential(account);

            return AzureResourceManager.authenticate(credential, profile).withSubscription(id);
        });
    }

    public TokenCredential getCredential(String subscriptionId) {
        CloudAccount account = cloudAccountRepository.findByAzureSubscriptionId(subscriptionId)
                .orElseThrow(() -> new IllegalArgumentException("Azure account not found for Subscription ID: " + subscriptionId));
        return buildCredential(account);
    }

    private TokenCredential buildCredential(CloudAccount account) {
        return new ClientSecretCredentialBuilder()
                .clientId(account.getAzureClientId())
                .clientSecret(account.getAzureClientSecret())
                .tenantId(account.getAzureTenantId())
                .build();
    }
}