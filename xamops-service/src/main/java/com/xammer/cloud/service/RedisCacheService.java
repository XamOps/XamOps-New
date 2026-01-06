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
        } catch (IOException e) {
            logger.error("Error deserializing cached data for key {}: {}", key, e.getMessage());
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
        } catch (IOException e) {
            logger.error("Error deserializing cached data for key {}: {}", key, e.getMessage());
        }
        return Optional.empty();
    }

    public <T> void put(String key, T value, int i) {
        try {
            String jsonData = objectMapper.writeValueAsString(value);
            redisTemplate.opsForValue().set(key, jsonData);
            logger.info("--- SAVED TO REDIS CACHE: {} ---", key);
        } catch (JsonProcessingException e) {
            logger.error("Error serializing data for caching for key {}: {}", key, e.getMessage());
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
    
}