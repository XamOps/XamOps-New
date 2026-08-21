package com.xammer.cloud.service.call;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Tracks who currently has a call-capable socket open.
 *
 * Held in Redis rather than in memory so it survives across replicas — two people
 * on different instances must still be able to see and ring each other. Each user
 * maps to a set of STOMP session ids, so multiple browser tabs do not knock each
 * other offline when one closes.
 *
 * Entries carry a TTL as a backstop: if an instance dies without firing disconnect
 * events, stale presence expires instead of lingering forever. The cost of a stale
 * entry is bounded — the call simply rings a ghost and lands as a missed call — so
 * the TTL is refreshed by an explicit client ping rather than anything heavier.
 */
@Service
public class CallPresenceService {

    private static final Logger log = LoggerFactory.getLogger(CallPresenceService.class);
    private static final String KEY_PREFIX = "xamops:call:presence:user:";

    private final StringRedisTemplate redis;

    /**
     * Must exceed the client ping interval with margin, or live users flicker
     * offline.
     *
     * Kept deliberately modest. On a hard browser kill there is no clean STOMP
     * disconnect, so this is how long a dead tab keeps looking ringable — and a ring
     * that lands on a ghost consumes the whole ring window before failing. At the
     * previous 900s a customer could sit through a full unanswered ring while real
     * agents were free. The client pings every 2 minutes, so this tolerates a
     * dropped ping plus a reconnect.
     */
    @Value("${xamops.calls.presence.ttl-seconds:300}")
    private long ttlSeconds;

    public CallPresenceService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private String key(Long userId) {
        return KEY_PREFIX + userId;
    }

    public void markOnline(Long userId, String sessionId) {
        if (userId == null || sessionId == null) {
            return;
        }
        try {
            String k = key(userId);
            redis.opsForSet().add(k, sessionId);
            redis.expire(k, Duration.ofSeconds(ttlSeconds));
        } catch (Exception e) {
            log.warn("Failed to mark user {} online for call presence: {}", userId, e.getMessage());
        }
    }

    /** Refreshes the TTL for a live session. Driven by the client's periodic ping. */
    public void touch(Long userId, String sessionId) {
        if (userId == null || sessionId == null) {
            return;
        }
        try {
            String k = key(userId);
            // Re-add rather than only bumping the TTL: if the key already expired, a
            // bare EXPIRE would leave the user invisible until they reconnect.
            redis.opsForSet().add(k, sessionId);
            redis.expire(k, Duration.ofSeconds(ttlSeconds));
        } catch (Exception e) {
            log.warn("Failed to refresh call presence for user {}: {}", userId, e.getMessage());
        }
    }

    public void markOffline(Long userId, String sessionId) {
        if (userId == null || sessionId == null) {
            return;
        }
        try {
            String k = key(userId);
            redis.opsForSet().remove(k, sessionId);
            Long remaining = redis.opsForSet().size(k);
            if (remaining == null || remaining == 0) {
                redis.delete(k);
            }
        } catch (Exception e) {
            log.warn("Failed to mark user {} offline for call presence: {}", userId, e.getMessage());
        }
    }

    public boolean isOnline(Long userId) {
        if (userId == null) {
            return false;
        }
        try {
            Long size = redis.opsForSet().size(key(userId));
            return size != null && size > 0;
        } catch (Exception e) {
            log.warn("Call presence lookup failed for user {}: {}", userId, e.getMessage());
            return false;
        }
    }

    /**
     * Filters a candidate ring set down to those actually reachable, preserving
     * order. Candidate sets here are small (tens of admins or account users), so
     * per-user lookups are cheaper than maintaining a global online index.
     */
    public Set<Long> filterOnline(Collection<Long> candidateUserIds) {
        Set<Long> online = new LinkedHashSet<>();
        if (candidateUserIds == null) {
            return online;
        }
        for (Long userId : candidateUserIds) {
            if (isOnline(userId)) {
                online.add(userId);
            }
        }
        return online;
    }
}
