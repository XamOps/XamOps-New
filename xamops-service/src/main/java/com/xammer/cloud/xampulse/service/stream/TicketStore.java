package com.xammer.cloud.xampulse.service.stream;

import java.time.Duration;
import java.util.Optional;

/** Minimal key-value store with single-use reads, backed by Redis in production. */
public interface TicketStore {

    void put(String key, String value, Duration ttl);

    /** Atomically reads and deletes. */
    Optional<String> getAndDelete(String key);

    /** Plain read; empty when absent or expired. */
    Optional<String> get(String key);

    /** Atomically stores only if the key is absent. @return true when this call stored it. */
    boolean putIfAbsent(String key, String value, Duration ttl);
}
