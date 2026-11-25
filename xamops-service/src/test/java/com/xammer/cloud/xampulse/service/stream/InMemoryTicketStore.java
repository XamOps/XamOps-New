package com.xammer.cloud.xampulse.service.stream;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Test stand-in for the Redis-backed store, with TTLs against an injectable clock. */
public final class InMemoryTicketStore implements TicketStore {

    private record Entry(String value, Instant expires) {}

    private final Map<String, Entry> map = new ConcurrentHashMap<>();
    private final Supplier<Instant> now;

    public InMemoryTicketStore() {
        this(Instant::now);
    }

    public InMemoryTicketStore(Supplier<Instant> now) {
        this.now = now;
    }

    private Optional<Entry> live(String key) {
        Entry e = map.get(key);
        if (e == null || !e.expires().isAfter(now.get())) {
            map.remove(key);
            return Optional.empty();
        }
        return Optional.of(e);
    }

    @Override public void put(String key, String value, Duration ttl) { map.put(key, new Entry(value, now.get().plus(ttl))); }
    @Override public Optional<String> getAndDelete(String key) { Optional<String> v = live(key).map(Entry::value); map.remove(key); return v; }
    @Override public Optional<String> get(String key) { return live(key).map(Entry::value); }
    @Override public boolean putIfAbsent(String key, String value, Duration ttl) {
        if (live(key).isPresent()) {
            return false;
        }
        put(key, value, ttl);
        return true;
    }
}
