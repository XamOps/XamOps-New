package com.xammer.cloud.service.autospotting.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * The provider-neutral cache of expensive eligibility-check successes (design-core.md §9.2, aws §7).
 * In LIST mode an evaluator answers only the checks it can see from the list's own describe and, for
 * every other check, consults this cache; a cached success is reused, otherwise the check is
 * {@code DEFERRED}. So a list never re-runs the ~10-15-call full pass per group (NFR-04).
 *
 * <p>Entries are keyed by (cloud account, group key, check code, live fingerprint) and expire after
 * 24 h. The live fingerprint is part of the key, so a configuration change invalidates every cached
 * success for that group automatically — a stale fingerprint simply misses.
 *
 * <p>Only successes are cached, and only a {@code PASS} is stored (never a blocker or a failed read,
 * so a transient failure can't poison a later list, aws §7). Redis is optional: when it is
 * unavailable the cache degrades to a miss, which yields {@code DEFERRED}, never a false pass.
 */
@Component
public class EligibilityCache {

    private static final Logger log = LoggerFactory.getLogger(EligibilityCache.class);

    /** The §9.2 24 h TTL for a cached expensive-check success. */
    static final Duration TTL = Duration.ofHours(24);

    private static final String KEY_PREFIX = "autospotting:elig:";
    private static final String PASS = "PASS";

    private final ObjectProvider<StringRedisTemplate> redis;

    public EligibilityCache(ObjectProvider<StringRedisTemplate> redis) {
        this.redis = redis;
    }

    /**
     * A cache key for one check of one group at one live fingerprint. The fingerprint is last so a
     * config change rolls the key over.
     */
    public static String key(Long cloudAccountId, String groupKey, String checkCode, String liveFingerprint) {
        return KEY_PREFIX + cloudAccountId + ":" + groupKey + ":" + checkCode + ":"
                + Objects.requireNonNullElse(liveFingerprint, "");
    }

    /** True when a {@code PASS} is cached for this check at this fingerprint. A miss (or no Redis) is false. */
    public boolean isPassCached(Long cloudAccountId, String groupKey, String checkCode, String liveFingerprint) {
        StringRedisTemplate template = redis.getIfAvailable();
        if (template == null) {
            return false;
        }
        try {
            String value = template.opsForValue().get(key(cloudAccountId, groupKey, checkCode, liveFingerprint));
            return PASS.equals(value);
        } catch (RuntimeException e) {
            log.debug("Eligibility cache read failed for {}:{}; treating as a miss", groupKey, checkCode);
            return false;
        }
    }

    /** Record a check {@code PASS} for 24 h. Only ever called for a success (§9.2). A Redis fault is swallowed. */
    public void recordPass(Long cloudAccountId, String groupKey, String checkCode, String liveFingerprint) {
        StringRedisTemplate template = redis.getIfAvailable();
        if (template == null) {
            return;
        }
        try {
            template.opsForValue().set(key(cloudAccountId, groupKey, checkCode, liveFingerprint), PASS, TTL);
        } catch (RuntimeException e) {
            log.debug("Eligibility cache write failed for {}:{}; continuing without it", groupKey, checkCode);
        }
    }

    /** Read the raw cached value (for tests and diagnostics); empty on miss or no Redis. */
    public Optional<String> peek(Long cloudAccountId, String groupKey, String checkCode, String liveFingerprint) {
        StringRedisTemplate template = redis.getIfAvailable();
        if (template == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(
                    template.opsForValue().get(key(cloudAccountId, groupKey, checkCode, liveFingerprint)));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
