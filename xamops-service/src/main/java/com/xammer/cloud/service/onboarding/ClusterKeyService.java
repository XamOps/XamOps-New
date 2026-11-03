package com.xammer.cloud.service.onboarding;

import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;

/**
 * Owns the lifecycle of a cluster's two long-lived agent keys (design §1.2, §4.6):
 * the read-only <em>data</em> key ({@code agent_api_key}) and the <em>automation</em> key
 * ({@code automation_agent_api_key}). It is the single place that mints, rotates and revokes
 * them; {@code ClusterIntegrationService} delegates its data-key operations here.
 *
 * <p>Keys are {@code xamops_} + 32 random bytes, URL-safe base64 without padding — the same
 * shape the installer and agents already expect. Get-or-create is idempotent so a re-connect
 * does not break a running agent.</p>
 */
@Service
public class ClusterKeyService {

    private static final Logger logger = LoggerFactory.getLogger(ClusterKeyService.class);

    private static final int KEY_BYTES = 32; // 256 bits
    public static final String KEY_PREFIX = "xamops_";

    private final KubernetesClusterConfigRepository clusterConfigRepository;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Clock clock;

    public ClusterKeyService(KubernetesClusterConfigRepository clusterConfigRepository, Clock clock) {
        this.clusterConfigRepository = clusterConfigRepository;
        this.clock = clock;
    }

    /** Mint a fresh {@code xamops_} key. */
    public String generateKey() {
        byte[] bytes = new byte[KEY_BYTES];
        secureRandom.nextBytes(bytes);
        return KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // ===== Data key =====

    /** Idempotent: returns the existing data key if present, else mints and stores one. */
    @Transactional
    public String getOrCreateDataKey(KubernetesClusterConfig config) {
        if (config.getAgentApiKey() != null && !config.getAgentApiKey().isEmpty()) {
            return config.getAgentApiKey();
        }
        return rotateDataKey(config);
    }

    /** Always mints a new data key, invalidating the old one. */
    @Transactional
    public String rotateDataKey(KubernetesClusterConfig config) {
        String key = generateKey();
        config.setAgentApiKey(key);
        config.setAgentKeyIssuedAt(Instant.now(clock));
        clusterConfigRepository.save(config);
        logger.info("Issued data key for cluster {} (prefix {}...)", config.getClusterName(), prefix(key));
        return key;
    }

    @Transactional
    public void revokeDataKey(KubernetesClusterConfig config) {
        config.setAgentApiKey(null);
        clusterConfigRepository.save(config);
        logger.info("Revoked data key for cluster {}", config.getClusterName());
    }

    // ===== Automation key =====

    @Transactional
    public String getOrCreateAutomationKey(KubernetesClusterConfig config) {
        if (config.getAutomationAgentApiKey() != null && !config.getAutomationAgentApiKey().isEmpty()) {
            return config.getAutomationAgentApiKey();
        }
        return rotateAutomationKey(config);
    }

    @Transactional
    public String rotateAutomationKey(KubernetesClusterConfig config) {
        String key = generateKey();
        config.setAutomationAgentApiKey(key);
        config.setAutomationKeyIssuedAt(Instant.now(clock));
        clusterConfigRepository.save(config);
        logger.info("Issued automation key for cluster {} (prefix {}...)", config.getClusterName(), prefix(key));
        return key;
    }

    @Transactional
    public void revokeAutomationKey(KubernetesClusterConfig config) {
        config.setAutomationAgentApiKey(null);
        clusterConfigRepository.save(config);
        logger.info("Revoked automation key for cluster {}", config.getClusterName());
    }

    /** Short, log-safe prefix of a key — never the full value. */
    public static String prefix(String key) {
        if (key == null) {
            return "none";
        }
        return key.substring(0, Math.min(KEY_PREFIX.length() + 4, key.length()));
    }
}
