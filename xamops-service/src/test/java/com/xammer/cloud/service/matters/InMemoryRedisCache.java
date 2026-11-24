package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.service.RedisCacheService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A {@link RedisCacheService} for the Datastores service tests. Values are kept as JSON, as Redis keeps
 * them, so every cached DTO really round-trips through Jackson. Every get and put is recorded, in order,
 * in {@link #events}, which a test can share with its other collaborators to check call order.
 *
 * <p>Unlike the real service, a value that does not deserialize fails the test instead of reading as a
 * miss.
 */
class InMemoryRedisCache extends RedisCacheService {

    /** One write: the key, the JSON written and the TTL in minutes. */
    record Put(String key, String json, int minutes) {
    }

    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, String> store = new LinkedHashMap<>();

    final List<String> gets = new ArrayList<>();
    final List<Put> puts = new ArrayList<>();
    final List<String> events;

    InMemoryRedisCache() {
        this(new ArrayList<>());
    }

    InMemoryRedisCache(List<String> events) {
        super(null, new ObjectMapper());
        this.events = events;
    }

    @Override
    public <T> Optional<T> get(String key, TypeReference<T> typeReference) {
        gets.add(key);
        events.add("get " + key);
        String raw = store.get(key);
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.readValue(raw, typeReference));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cached value for " + key + " did not deserialize", e);
        }
    }

    @Override
    public <T> void put(String key, T value, int minutes) {
        String raw = write(value);
        store.put(key, raw);
        puts.add(new Put(key, raw, minutes));
        events.add("put " + key);
    }

    /** Stores a value as an earlier request would have, without recording a put. */
    void seed(String key, Object value) {
        store.put(key, write(value));
    }

    boolean contains(String key) {
        return store.containsKey(key);
    }

    /** The puts made under one key. */
    List<Put> putsFor(String key) {
        return puts.stream().filter(put -> put.key().equals(key)).toList();
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
