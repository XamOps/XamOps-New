package com.xammer.cloud.service.automation.workload.api;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * A short-lived, bounded, in-process answer cache for the read-only history endpoints — the
 * {@link VerticalAutomationReadService} probe-cache pattern (a {@link ConcurrentHashMap} of entries
 * with a monotonic deadline), with no new dependency.
 *
 * <p>Keys start with {@code clientId:clusterConfigId:}, so one tenant can never be served another
 * tenant's answer. Custom windows make the key space open-ended, so expired entries are swept on
 * insert and a full cache stops admitting new entries rather than growing: an uncached answer is
 * only slower, never wrong.
 */
final class ResponseCache<V> {

    /**
     * Per cache. An answer is a few hundred points (a sparklines batch for a large cluster, a few
     * thousand), and every entry dies within the TTL, so this bounds memory without ever making an
     * answer wrong.
     */
    static final int MAX_ENTRIES = 128;

    private final Map<String, Entry<V>> entries = new ConcurrentHashMap<>();
    private final long ttlNanos;
    private final LongSupplier nanoClock;

    ResponseCache(Duration ttl, LongSupplier nanoClock) {
        this.ttlNanos = ttl.toNanos();
        this.nanoClock = nanoClock;
    }

    /** The cached answer, or null when there is none or it has expired. */
    V get(String key) {
        Entry<V> entry = entries.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.isExpiredAt(nanoClock.getAsLong())) {
            entries.remove(key, entry);
            return null;
        }
        return entry.value();
    }

    void put(String key, V value) {
        long now = nanoClock.getAsLong();
        if (entries.size() >= MAX_ENTRIES) {
            entries.values().removeIf(entry -> entry.isExpiredAt(now));
            if (entries.size() >= MAX_ENTRIES) {
                return;
            }
        }
        entries.put(key, new Entry<>(value, now + ttlNanos));
    }

    int size() {
        return entries.size();
    }

    private record Entry<V>(V value, long expiresAtNanos) {

        private boolean isExpiredAt(long nowNanos) {
            // Subtraction, not comparison: nanoTime wraps, and a difference stays correct across it.
            return nowNanos - expiresAtNanos >= 0;
        }
    }
}
