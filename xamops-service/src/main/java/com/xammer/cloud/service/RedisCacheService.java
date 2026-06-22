package com.xammer.cloud.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.util.concurrent.TimeUnit;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.TimeUnit; // ✅ Added Import

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
                logger.info("--- LOADING FROM REDIS CACHE: {} ---", key);
                return Optional.of(objectMapper.readValue(jsonData, clazz));
            }
        } catch (Exception e) {
            logger.warn("Error retrieving/deserializing cached data for key {}: {}", key, e.getMessage());
        }
        return Optional.empty();
    }

    public <T> Optional<T> get(String key, TypeReference<T> typeReference) {
        try {
            String jsonData = redisTemplate.opsForValue().get(key);
            if (jsonData != null) {
                logger.info("--- LOADING FROM REDIS CACHE (TypeReference): {} ---", key);
                return Optional.of(objectMapper.readValue(jsonData, typeReference));
            }
        } catch (Exception e) {
            logger.warn("Error retrieving/deserializing cached data for key {}: {}", key, e.getMessage());
        }
        return Optional.empty();
    }

    // ✅ FIX: Renamed parameter 'i' to 'minutes' and applied it to redisTemplate
    public <T> void put(String key, T value, int minutes) {
        try {
            String jsonData = objectMapper.writeValueAsString(value);
            // ✅ FIX: Added timeout arguments so cache actually expires
            redisTemplate.opsForValue().set(key, jsonData, minutes, TimeUnit.MINUTES);
            logger.info("--- SAVED TO REDIS CACHE: {} (TTL: {} mins) ---", key, minutes);
        } catch (Exception e) {
            logger.warn("Error serializing/saving data for caching for key {}: {}", key, e.getMessage());
        }
    }

    public void evict(String key) {
        if (Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
            redisTemplate.delete(key);
            logger.info("--- EVICTED FROM REDIS CACHE: {} ---", key);
        } else {
            logger.warn("--- CACHE EVICTION SKIPPED: KEY '{}' NOT FOUND ---", key);
        }
    }

    /**
     * Evicts every key matching the given glob-style pattern (e.g. "gcp:billing-summary:my-project:*").
     * Returns the number of keys removed.
     */
    public long evictByPattern(String pattern) {
        try {
            java.util.Set<String> keys = redisTemplate.keys(pattern);
            if (keys == null || keys.isEmpty()) {
                logger.info("--- NO REDIS KEYS MATCHED PATTERN: {} ---", pattern);
                return 0L;
            }
            Long removed = redisTemplate.delete(keys);
            logger.info("--- EVICTED {} REDIS KEYS FOR PATTERN: {} ---", removed, pattern);
            return removed == null ? 0L : removed;
        } catch (Exception e) {
            logger.warn("Error evicting Redis keys for pattern {}: {}", pattern, e.getMessage());
            return 0L;
        }
    }

    // --- ✅ ADD THESE NEW METHODS AT THE BOTTOM ---

    public void saveOtp(String username, String otp) {
        // Stores OTP for exactly 5 minutes (300 seconds)
        String key = "OTP:" + username;
        redisTemplate.opsForValue().set(key, otp, 300, TimeUnit.SECONDS);
    }

    public String getOtp(String username) {
        String key = "OTP:" + username;
        Object otp = redisTemplate.opsForValue().get(key);
        return otp != null ? otp.toString() : null;
    }

    public void deleteOtp(String username) {
        String key = "OTP:" + username;
        redisTemplate.delete(key);
    }

    // --- Login OTP (email-based 2FA login) ---

    public void saveLoginOtp(String email, String otp) {
        String key = "LOGIN_OTP:" + email;
        redisTemplate.opsForValue().set(key, otp, 300, TimeUnit.SECONDS);
    }

    public String getLoginOtp(String email) {
        String key = "LOGIN_OTP:" + email;
        Object otp = redisTemplate.opsForValue().get(key);
        return otp != null ? otp.toString() : null;
    }

    public void deleteLoginOtp(String email) {
        String key = "LOGIN_OTP:" + email;
        redisTemplate.delete(key);
    }

    public void savePreAuthFlag(String email) {
        String key = "PRE_AUTH:" + email;
        redisTemplate.opsForValue().set(key, "true", 300, TimeUnit.SECONDS);
    }

    public boolean getPreAuthFlag(String email) {
        String key = "PRE_AUTH:" + email;
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    public void deletePreAuthFlag(String email) {
        String key = "PRE_AUTH:" + email;
        redisTemplate.delete(key);
    }

    public boolean hasKey(String key) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    public void setFlag(String key, int ttlMinutes) {
        redisTemplate.opsForValue().set(key, "1", ttlMinutes, TimeUnit.MINUTES);
    }

}