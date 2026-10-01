package com.xammer.cloud.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Stops the same mobile notification being sent to the same person over and over.
 *
 * <p>Why this is needed: the disk alert scan runs every 4 hours, and a volume hovering around the
 * 80% threshold re-crosses it repeatedly — {@code persistAlertState} clears {@code resolvedAt} and
 * the next breach counts as new. Email absorbs that; WhatsApp does not. Each send is billed, and six
 * identical messages a day is how people learn to mute a channel, which costs more than the money.
 *
 * <p>Two stores, mirroring {@code PermissionGapRegistry}:
 * <ul>
 *   <li>Redis when available, so the throttle holds across replicas and restarts. A notification
 *       suppressed on one instance must stay suppressed when the next scan lands on another.</li>
 *   <li>An in-process map as fallback, so a Redis outage degrades to per-instance throttling rather
 *       than to no throttling.</li>
 * </ul>
 *
 * <p>The Redis path uses {@code SET NX} with a TTL, which is atomic: two replicas scanning the same
 * account concurrently cannot both decide they are the one allowed to send.
 *
 * <p><b>This throttles one channel, not the alert.</b> Email still goes out on every scan with the
 * full per-resource detail, so suppressing a WhatsApp nudge never hides a problem — it only stops
 * the phone buzzing about something already reported.
 */
@Service
public class MessageThrottle {

    private static final Logger log = LoggerFactory.getLogger(MessageThrottle.class);
    private static final String KEY_PREFIX = "xamops:msgthrottle:";

    /** Bounds the fallback map if Redis is unavailable for a long stretch. */
    private static final int MAX_LOCAL_ENTRIES = 10_000;

    private final ObjectProvider<StringRedisTemplate> redisProvider;

    /** key -> epoch millis of the last allowed send. Used only when Redis is absent. */
    private final Map<String, Long> local = new ConcurrentHashMap<>();

    public MessageThrottle(ObjectProvider<StringRedisTemplate> redisProvider) {
        this.redisProvider = redisProvider;
    }

    /**
     * Claims the right to send, and records the claim.
     *
     * <p>Returns true at most once per {@code cooldown} for a given key. Call this immediately
     * before sending: a true result marks the key as spent whether or not the send then succeeds.
     * That is deliberate — retrying a failed provider call on the next scan is the desired
     * behaviour, but retrying it immediately in a loop is not.
     *
     * @param key      stable identity of the thing being notified about, e.g. kind + account + recipient
     * @param cooldown minimum gap between sends; zero or negative disables throttling entirely
     * @return true if the caller may send now
     */
    public boolean tryAcquire(String key, Duration cooldown) {
        if (key == null || cooldown == null || cooldown.isZero() || cooldown.isNegative()) {
            return true;
        }
        String fullKey = KEY_PREFIX + key;

        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis != null) {
            try {
                // Atomic claim: only the first caller within the window gets true.
                Boolean claimed = redis.opsForValue()
                        .setIfAbsent(fullKey, Long.toString(System.currentTimeMillis()), cooldown);
                return Boolean.TRUE.equals(claimed);
            } catch (Exception e) {
                // Fall through to the local map rather than silently sending unthrottled.
                log.debug("Redis unavailable for message throttle, falling back to in-process: {}",
                        e.getMessage());
            }
        }
        return tryAcquireLocally(fullKey, cooldown);
    }

    private boolean tryAcquireLocally(String fullKey, Duration cooldown) {
        long now = System.currentTimeMillis();
        long windowMs = cooldown.toMillis();
        AtomicBoolean allowed = new AtomicBoolean(false);

        // compute() holds the bin lock for this key, so two threads cannot both be allowed.
        //
        // The decision is reported through the flag rather than by comparing the returned value to
        // `now`: two calls in the same millisecond produce an identical timestamp, so a value
        // comparison cannot tell "I just claimed it" from "someone else already had it" and would
        // let the second caller through.
        local.compute(fullKey, (key, previous) -> {
            if (previous == null || now - previous >= windowMs) {
                allowed.set(true);
                return now;
            }
            return previous;
        });

        if (allowed.get() && local.size() > MAX_LOCAL_ENTRIES) {
            local.entrySet().removeIf(e -> now - e.getValue() >= windowMs);
        }
        return allowed.get();
    }

    /** Forgets a key, so the next call may send immediately. Exposed for tests and manual replay. */
    public void clear(String key) {
        if (key == null) {
            return;
        }
        String fullKey = KEY_PREFIX + key;
        local.remove(fullKey);
        try {
            StringRedisTemplate redis = redisProvider.getIfAvailable();
            if (redis != null) {
                redis.delete(fullKey);
            }
        } catch (Exception e) {
            log.debug("Could not clear throttle key {}: {}", key, e.getMessage());
        }
    }
}
