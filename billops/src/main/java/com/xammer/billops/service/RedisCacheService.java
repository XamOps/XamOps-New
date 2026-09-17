// In: billops/src/main/java/com/xammer/billops/service/RedisCacheService.java
package com.xammer.billops.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.TimeUnit; // <-- Make sure to import this

@Service
public class RedisCacheService {

    private static final Logger logger = LoggerFactory.getLogger(RedisCacheService.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisCacheService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public <T> Optional<T> get(String key, Class<T> clazz) {
        try {
            String jsonData = redisTemplate.opsForValue().get(key);
            if (jsonData != null) {
                logger.info("--- BILLOPS: LOADING FROM REDIS CACHE: {} ---", key);
                return Optional.of(objectMapper.readValue(jsonData, clazz));
            }
        } catch (IOException e) {
            logger.error("BILLOPS: Error deserializing cached data for key {}: {}", key, e.getMessage());
        }
        return Optional.empty();
    }

    public <T> Optional<T> get(String key, TypeReference<T> typeReference) {
        try {
            String jsonData = redisTemplate.opsForValue().get(key);
            if (jsonData != null) {
                logger.info("--- BILLOPS: LOADING FROM REDIS CACHE (TypeReference): {} ---", key);
                return Optional.of(objectMapper.readValue(jsonData, typeReference));
            }
        } catch (IOException e) {
            logger.error("BILLOPS: Error deserializing cached data for key {}: {}", key, e.getMessage());
        }
        return Optional.empty();
    }

    public <T> void put(String key, T value, long ttlInMinutes) {
        try {
            String jsonData = objectMapper.writeValueAsString(value);
            redisTemplate.opsForValue().set(key, jsonData, ttlInMinutes, TimeUnit.MINUTES);
            logger.info("--- BILLOPS: SAVED TO REDIS CACHE: {} (TTL: {} min) ---", key, ttlInMinutes);
        } catch (JsonProcessingException e) {
            logger.error("BILLOPS: Error serializing data for caching for key {}: {}", key, e.getMessage());
        }
    }

    public void evict(String key) {
        if (Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
            redisTemplate.delete(key);
            logger.info("--- BILLOPS: EVICTED FROM REDIS CACHE: {} ---", key);
        }
    }

    /**
     * Evicts every key beginning with {@code prefix}. Needed for per-user cached lists whose
     * key carries a variable suffix (e.g. "...:user:152"): a single-key evict cannot clear all
     * of them because the eviction site doesn't know every user id. Uses SCAN via
     * {@code keys(prefix*)} then deletes the matches.
     */
    public void evictByPrefix(String prefix) {
        try {
            java.util.Set<String> keys = redisTemplate.keys(prefix + "*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
                logger.info("--- BILLOPS: EVICTED {} KEYS BY PREFIX: {}* ---", keys.size(), prefix);
            }
        } catch (Exception e) {
            logger.warn("BILLOPS: prefix eviction failed for {}*: {}", prefix, e.getMessage());
        }
    }
}