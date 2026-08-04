package com.xammer.cloud.permissions;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers which cloud API calls are currently being refused for each account.
 *
 * Two stores, on purpose:
 *
 *  - An in-process map, which is the hot path. Every AWS call in the service goes
 *    through the interceptor that feeds this, so it has to cost approximately
 *    nothing on the overwhelmingly common success case.
 *  - Redis, so that a gap recorded by the background refresh on one replica is
 *    visible to the API request that lands on another, and so it survives a
 *    restart. Writes are throttled per action; a broken dashboard refresh can
 *    generate thousands of identical denials a minute and none of them need to
 *    reach Redis individually.
 *
 * Entries carry a TTL so a permission that was quietly restored stops being
 * reported even if no code path happened to re-run and clear it explicitly.
 */
@Service
public class PermissionGapRegistry {

    private static final Logger log = LoggerFactory.getLogger(PermissionGapRegistry.class);
    private static final String KEY_PREFIX = "xamops:permgaps:";

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final ObjectMapper mapper;

    /** accountId -> (action -> gap). Authoritative for this JVM, best-effort across the fleet. */
    private final Map<String, Map<String, PermissionGap>> local = new ConcurrentHashMap<>();

    /** accountId|action -> last time this entry was pushed to Redis, to throttle writes. */
    private final Map<String, Long> lastFlushed = new ConcurrentHashMap<>();

    @Value("${xamops.permission-gaps.ttl-hours:24}")
    private long ttlHours;

    /** Minimum gap between Redis writes for the same action. */
    @Value("${xamops.permission-gaps.flush-interval-seconds:60}")
    private long flushIntervalSeconds;

    @Value("${xamops.permission-gaps.enabled:true}")
    private boolean enabled;

    public PermissionGapRegistry(ObjectProvider<StringRedisTemplate> redisProvider) {
        this.redisProvider = redisProvider;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public boolean isEnabled() {
        return enabled;
    }

    // ─── Write path ───────────────────────────────────────────────────────────

    /** Records (or folds into) a denial. Never throws — this runs inside an SDK interceptor. */
    public void record(PermissionGap gap) {
        if (!enabled || gap == null || gap.getAccountId() == null || gap.getAction() == null) {
            return;
        }
        try {
            Map<String, PermissionGap> forAccount =
                    local.computeIfAbsent(gap.getAccountId(), k -> new ConcurrentHashMap<>());

            PermissionGap merged = forAccount.compute(gap.getAction(), (action, existing) -> {
                if (existing == null) {
                    if (gap.getOccurrences() <= 0) {
                        gap.setOccurrences(1);
                    }
                    return gap;
                }
                existing.mergeSighting(gap);
                return existing;
            });

            flushToRedisIfDue(merged);
        } catch (Exception e) {
            log.debug("Could not record permission gap for account {}: {}", gap.getAccountId(), e.getMessage());
        }
    }

    /**
     * Clears a gap after the same call succeeds again.
     *
     * Deliberately only acts on actions this JVM knows are failing: a success is
     * the common case, and paying a Redis round trip on every successful AWS call
     * would be a real cost for no benefit. Gaps recorded on another replica are
     * cleaned up by the TTL or by an explicit re-check from the UI.
     */
    public void resolveIfKnown(String accountId, String action) {
        if (!enabled || accountId == null || action == null) {
            return;
        }
        Map<String, PermissionGap> forAccount = local.get(accountId);
        if (forAccount == null || forAccount.remove(action) == null) {
            return;
        }
        lastFlushed.remove(accountId + "|" + action);
        try {
            StringRedisTemplate redis = redisProvider.getIfAvailable();
            if (redis != null) {
                redis.opsForHash().delete(KEY_PREFIX + accountId, action);
            }
            log.info("Permission restored for account {} — {} now succeeds", accountId, action);
        } catch (Exception e) {
            log.debug("Could not clear permission gap {} for account {}: {}", action, accountId, e.getMessage());
        }
    }

    /** Drops every recorded gap for an account, so the next data refresh re-tests from scratch. */
    public void clear(String accountId) {
        if (accountId == null) {
            return;
        }
        local.remove(accountId);
        lastFlushed.keySet().removeIf(key -> key.startsWith(accountId + "|"));
        try {
            StringRedisTemplate redis = redisProvider.getIfAvailable();
            if (redis != null) {
                redis.delete(KEY_PREFIX + accountId);
            }
        } catch (Exception e) {
            log.debug("Could not clear permission gaps for account {}: {}", accountId, e.getMessage());
        }
    }

    private void flushToRedisIfDue(PermissionGap gap) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return;
        }
        String throttleKey = gap.getAccountId() + "|" + gap.getAction();
        long now = System.currentTimeMillis();
        Long previous = lastFlushed.get(throttleKey);
        if (previous != null && now - previous < flushIntervalSeconds * 1000L) {
            return;
        }
        lastFlushed.put(throttleKey, now);
        try {
            String key = KEY_PREFIX + gap.getAccountId();
            redis.opsForHash().put(key, gap.getAction(), mapper.writeValueAsString(gap));
            redis.expire(key, Duration.ofHours(ttlHours));
        } catch (Exception e) {
            log.debug("Could not persist permission gap {}: {}", gap.getAction(), e.getMessage());
        }
    }

    // ─── Read path ────────────────────────────────────────────────────────────

    /**
     * Current gaps for an account, newest sighting first. Merges what Redis knows
     * (other replicas, earlier runs) with what this JVM has seen since its last flush.
     */
    public List<PermissionGap> findByAccount(String accountId) {
        if (!enabled || accountId == null) {
            return List.of();
        }
        Map<String, PermissionGap> byAction = new LinkedHashMap<>();

        try {
            StringRedisTemplate redis = redisProvider.getIfAvailable();
            if (redis != null) {
                Map<Object, Object> stored = redis.opsForHash().entries(KEY_PREFIX + accountId);
                for (Map.Entry<Object, Object> entry : stored.entrySet()) {
                    try {
                        PermissionGap gap = mapper.readValue(String.valueOf(entry.getValue()), PermissionGap.class);
                        byAction.put(gap.getAction(), gap);
                    } catch (Exception parseError) {
                        log.debug("Skipping unreadable permission gap entry {}: {}", entry.getKey(),
                                parseError.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Redis unavailable for permission gaps, serving in-memory only: {}", e.getMessage());
        }

        Map<String, PermissionGap> inMemory = local.get(accountId);
        if (inMemory != null) {
            // The local copy is always at least as fresh as the flushed one.
            byAction.putAll(inMemory);
        }

        List<PermissionGap> gaps = new ArrayList<>(byAction.values());
        gaps.sort(Comparator.comparing(PermissionGap::getLastSeen,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return gaps;
    }
}
