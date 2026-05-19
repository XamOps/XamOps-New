package com.xammer.cloud.service;

import com.xammer.cloud.domain.LlmKeyGroup;
import com.xammer.cloud.domain.LlmProviderKey;
import com.xammer.cloud.repository.LlmProviderKeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
public class KeyPoolService {

    private static final Logger log = LoggerFactory.getLogger(KeyPoolService.class);

    private final LlmProviderKeyRepository keyRepo;
    private final EncryptionService encryptionService;
    // counter key = groupId + ":" + provider
    private final ConcurrentHashMap<String, AtomicInteger> roundRobinCounters = new ConcurrentHashMap<>();

    public KeyPoolService(LlmProviderKeyRepository keyRepo, EncryptionService encryptionService) {
        this.keyRepo = keyRepo;
        this.encryptionService = encryptionService;
    }

    /**
     * Get next key for a group + provider combination using the group's load balance strategy.
     * Returns null if the group has no stored keys for this provider (caller falls back to bearer token).
     */
    public SelectedKey getNextKey(LlmKeyGroup group, String provider) {
        List<LlmProviderKey> active = availableKeys(group.getId(), provider);
        if (active.isEmpty()) return null;

        String strategy = group.getLoadBalanceStrategy() != null ? group.getLoadBalanceStrategy() : "ROUND_ROBIN";
        String counterKey = group.getId() + ":" + provider;

        LlmProviderKey selected = switch (strategy) {
            case "LEAST_REQUESTS" -> leastRequests(active);
            case "RATE_LIMIT_AWARE" -> roundRobin(counterKey, active); // already filtered by availableKeys
            default -> roundRobin(counterKey, active);
        };

        keyRepo.incrementRequests(selected.getId());
        String decrypted = encryptionService.decrypt(selected.getEncryptedApiKey());
        return new SelectedKey(selected.getId(), decrypted);
    }

    public void markSuccess(Long keyId) {
        keyRepo.decrementRequests(keyId);
    }

    public void markRateLimited(Long keyId, int cooldownSeconds) {
        LocalDateTime until = LocalDateTime.now().plusSeconds(cooldownSeconds);
        keyRepo.updateStatusAndRateLimit(keyId, "RATE_LIMITED", until);
        log.warn("Key {} marked rate-limited until {}", keyId, until);
    }

    public void markError(Long keyId) {
        keyRepo.decrementRequests(keyId);
    }

    private List<LlmProviderKey> availableKeys(Long groupId, String provider) {
        return keyRepo.findByKeyGroupIdAndProviderAndStatusOrderByCreatedAtAsc(groupId, provider, "ACTIVE")
                .stream()
                .filter(k -> k.getRateLimitedUntil() == null
                        || k.getRateLimitedUntil().isBefore(LocalDateTime.now()))
                .collect(Collectors.toList());
    }

    private LlmProviderKey roundRobin(String counterKey, List<LlmProviderKey> keys) {
        AtomicInteger ctr = roundRobinCounters.computeIfAbsent(counterKey, k -> new AtomicInteger(0));
        return keys.get(ctr.getAndIncrement() % keys.size());
    }

    private LlmProviderKey leastRequests(List<LlmProviderKey> keys) {
        return keys.stream().min(Comparator.comparingInt(LlmProviderKey::getCurrentRequests)).orElseThrow();
    }

    @Scheduled(fixedDelay = 30_000)
    public void recoverRateLimitedKeys() {
        keyRepo.findAll().stream()
                .filter(k -> "RATE_LIMITED".equals(k.getStatus())
                        && k.getRateLimitedUntil() != null
                        && k.getRateLimitedUntil().isBefore(LocalDateTime.now()))
                .forEach(k -> {
                    keyRepo.updateStatusAndRateLimit(k.getId(), "ACTIVE", null);
                    log.info("Key {} recovered from rate limit", k.getId());
                });
    }

    public record SelectedKey(Long keyId, String decryptedKey) {}
}
