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

    /**
     * Cached credentials keyed by subscription id. Each {@code ClientSecretCredential}
     * carries its own token cache, so reusing the instance avoids a fresh AAD round trip
     * per ARM call.
     */
    private final Map<String, TokenCredential> credentialCache = new ConcurrentHashMap<>();

    private final CloudAccountRepository cloudAccountRepository;

    public AzureClientProvider(CloudAccountRepository cloudAccountRepository) {
        this.cloudAccountRepository = cloudAccountRepository;
    }

    public AzureResourceManager getAzureClient(String subscriptionId) {
        return clientCache.computeIfAbsent(subscriptionId, id -> {
            // This now correctly finds the account by the Subscription ID string
            CloudAccount account = cloudAccountRepository.findByAzureSubscriptionId(id)
                    .orElseThrow(
                            () -> new IllegalArgumentException("Azure account not found for Subscription ID: " + id));

            AzureProfile profile = new AzureProfile(account.getAzureTenantId(), account.getAzureSubscriptionId(),
                    AzureEnvironment.AZURE);
            TokenCredential credential = buildCredential(account);

            return AzureResourceManager.authenticate(credential, profile).withSubscription(id);
        });
    }

    /**
     * Returns a cached credential for a subscription.
     *
     * <p>Caching matters: {@code ClientSecretCredential} maintains its own internal token
     * cache, so rebuilding one on every call — as this previously did — threw that cache
     * away and forced a fresh AAD token request per ARM call.
     */
    public TokenCredential getCredential(String subscriptionId) {
        return credentialCache.computeIfAbsent(subscriptionId, id -> {
            CloudAccount account = cloudAccountRepository.findByAzureSubscriptionId(id)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Azure account not found for Subscription ID: " + id));
            return buildCredential(account);
        });
    }

    /**
     * Returns a cached credential for an already-resolved account, keyed by subscription id
     * so it shares the cache with {@link #getCredential(String)}.
     */
    public TokenCredential getCredential(CloudAccount account) {
        String subscriptionId = account.getAzureSubscriptionId();
        if (subscriptionId == null || subscriptionId.isBlank()) {
            // Nothing stable to key on; build a one-off credential.
            return buildCredential(account);
        }
        return credentialCache.computeIfAbsent(subscriptionId, id -> buildCredential(account));
    }

    /**
     * Drops cached clients and credentials for a subscription. Call after rotating the
     * service-principal secret, otherwise the stale credential is reused until restart.
     */
    public void evict(String subscriptionId) {
        clientCache.remove(subscriptionId);
        credentialCache.remove(subscriptionId);
    }

    private TokenCredential buildCredential(CloudAccount account) {
        return new ClientSecretCredentialBuilder()
                .clientId(account.getAzureClientId())
                .clientSecret(account.getAzureClientSecret())
                .tenantId(account.getAzureTenantId())
                .build();
    }
}