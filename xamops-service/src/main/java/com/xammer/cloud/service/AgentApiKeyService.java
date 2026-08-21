package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Mints, rotates and revokes account-scoped agent API keys.
 *
 * These exist so a VM agent (disk, database) can prove which account it belongs to. Kubernetes
 * clusters already had per-cluster keys via {@link ClusterIntegrationService}; VM agents had no
 * row to keep one on and so were stuck on the shared global key, which proves nothing about
 * identity and is published in the install scripts.
 *
 * Keys are stored as issued rather than hashed, matching the existing
 * {@code kubernetes_cluster_config.agent_api_key} column. That is a deliberate, documented gap:
 * hashing is a worthwhile follow-up but it invalidates every key already handed to a customer,
 * so it needs its own rotation window rather than being smuggled in here.
 */
@Service
public class AgentApiKeyService {

    private static final Logger logger = LoggerFactory.getLogger(AgentApiKeyService.class);

    /**
     * 24 random bytes → 48 hex characters. With the {@code xak_} prefix that is 52 characters,
     * inside the 64-character column. Mirrors the sizing already used for ISV product keys.
     */
    private static final int KEY_BYTES = 24;
    private static final String KEY_PREFIX = "xak_";
    private static final int MAX_COLLISION_RETRIES = 5;

    private final CloudAccountRepository cloudAccountRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public AgentApiKeyService(CloudAccountRepository cloudAccountRepository) {
        this.cloudAccountRepository = cloudAccountRepository;
    }

    /**
     * Returns the account's current agent key, creating one on first request.
     *
     * Idempotent so that showing an install command twice does not silently invalidate the
     * agent a customer already deployed — use {@link #rotate} when replacement is the intent.
     */
    @Transactional
    public String getOrCreate(Long cloudAccountId) {
        CloudAccount account = cloudAccountRepository.findById(cloudAccountId)
                .orElseThrow(() -> new IllegalArgumentException("Cloud account not found: " + cloudAccountId));

        if (account.getAgentApiKey() != null && !account.getAgentApiKey().isBlank()) {
            return account.getAgentApiKey();
        }
        return assignNewKey(account);
    }

    /**
     * Replaces the account's key. Agents still holding the old one stop authenticating on their
     * next request, which is the point — this is the revocation path for a leaked key.
     */
    @Transactional
    public String rotate(Long cloudAccountId) {
        CloudAccount account = cloudAccountRepository.findById(cloudAccountId)
                .orElseThrow(() -> new IllegalArgumentException("Cloud account not found: " + cloudAccountId));
        logger.info("Rotating agent API key for cloud account {} — agents using the previous key "
                + "will stop authenticating until reconfigured", cloudAccountId);
        return assignNewKey(account);
    }

    /** Removes the account's key entirely. Its VM agents can no longer authenticate. */
    @Transactional
    public void revoke(Long cloudAccountId) {
        Optional<CloudAccount> account = cloudAccountRepository.findById(cloudAccountId);
        if (account.isEmpty()) return;
        account.get().setAgentApiKey(null);
        account.get().setAgentKeyIssuedAt(null);
        cloudAccountRepository.save(account.get());
        logger.info("Revoked agent API key for cloud account {}", cloudAccountId);
    }

    private String assignNewKey(CloudAccount account) {
        String key = generateUniqueKey();
        account.setAgentApiKey(key);
        account.setAgentKeyIssuedAt(Instant.now());
        cloudAccountRepository.save(account);
        logger.info("Issued agent API key for cloud account {} (prefix {})",
                account.getId(), key.substring(0, Math.min(12, key.length())));
        return key;
    }

    /**
     * The column is unique, so a collision would surface as a constraint violation on save.
     * At 192 bits of entropy that will not happen in practice; retrying is here so that if it
     * somehow did, the caller gets a key rather than an exception.
     */
    private String generateUniqueKey() {
        for (int attempt = 0; attempt < MAX_COLLISION_RETRIES; attempt++) {
            String candidate = generateKey();
            if (!cloudAccountRepository.existsByAgentApiKey(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not generate a unique agent API key");
    }

    private String generateKey() {
        byte[] bytes = new byte[KEY_BYTES];
        secureRandom.nextBytes(bytes);
        return KEY_PREFIX + HexFormat.of().formatHex(bytes);
    }
}
