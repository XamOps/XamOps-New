package com.xammer.cloud.service.autospotting.gcp;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * A small in-memory cache for successful GCP reads (24 h machine types, SKU indexes, backend
 * services). Only successes are stored: a loader that throws leaves nothing behind (core §9.5).
 */
final class TtlCache<K, V> {
    private record Entry<V>(V value, Instant expiresAt) {
    }

    private final Map<K, Entry<V>> entries = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final Clock clock;

    TtlCache(Duration ttl, Clock clock) {
        this.ttl = ttl;
        this.clock = clock;
    }

    V get(K key, Supplier<V> loader) {
        Instant now = clock.instant();
        Entry<V> e = entries.get(key);
        if (e != null && e.expiresAt().isAfter(now)) {
            return e.value();
        }
        V value = loader.get();
        entries.put(key, new Entry<>(value, now.plus(ttl)));
        return value;
    }

    boolean contains(K key) {
        Entry<V> e = entries.get(key);
        return e != null && e.expiresAt().isAfter(clock.instant());
    }
}
