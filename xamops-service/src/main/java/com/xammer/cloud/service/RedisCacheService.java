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

    /** Keys pulled per SCAN round trip, and per DEL batch. */
    private static final int SCAN_BATCH_SIZE = 500;

    /**
     * Evicts every key matching the given glob-style pattern (e.g. "gcp:billing-summary:my-project:*").
     * Returns the number of keys removed.
     * <p>
     * Uses SCAN rather than KEYS. KEYS is O(N) over the whole keyspace and runs on Redis's
     * single command thread, so it stalls every other client for the duration. That matters
     * here because this instance also backs Spring Session: a stalled Redis means
     * {@code SessionRepositoryFilter} blocks on session lookup for every inbound HTTP
     * request, which surfaces as app-wide 500s with
     * {@code RedisCommandTimeoutException: Command timed out after 1 minute(s)}.
     * {@code evictAllCostCaches} issues nine of these patterns per project, so the old
     * implementation could block Redis dozens of times in a row during the nightly refresh.
     * <p>
     * SCAN iterates in small cursor-bounded chunks instead, and deletes are batched.
     */
    public long evictByPattern(String pattern) {
        if (pattern == null || pattern.isBlank() || "*".equals(pattern.trim())) {
            logger.warn("--- REFUSING UNBOUNDED REDIS EVICTION FOR PATTERN: '{}' ---", pattern);
            return 0L;
        }

        long removed = 0L;
        try {
            org.springframework.data.redis.core.ScanOptions options =
                    org.springframework.data.redis.core.ScanOptions.scanOptions()
                            .match(pattern)
                            .count(SCAN_BATCH_SIZE)
                            .build();

            java.util.List<String> batch = new java.util.ArrayList<>(SCAN_BATCH_SIZE);
            try (org.springframework.data.redis.core.Cursor<String> cursor = redisTemplate.scan(options)) {
                while (cursor.hasNext()) {
                    batch.add(cursor.next());
                    if (batch.size() >= SCAN_BATCH_SIZE) {
                        removed += deleteBatch(batch);
                        batch.clear();
                    }
                }
            }
            if (!batch.isEmpty()) {
                removed += deleteBatch(batch);
            }

            if (removed == 0L) {
                logger.info("--- NO REDIS KEYS MATCHED PATTERN: {} ---", pattern);
            } else {
                logger.info("--- EVICTED {} REDIS KEYS FOR PATTERN: {} ---", removed, pattern);
            }
            return removed;
        } catch (Exception e) {
            logger.warn("Error evicting Redis keys for pattern {}: {}", pattern, e.getMessage());
            return removed;
        }
    }

    private long deleteBatch(java.util.List<String> keys) {
        Long deleted = redisTemplate.delete(keys);
        return deleted == null ? 0L : deleted;
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

    // --- Generic string cache (used by trial status enforcement) ---

    public void putString(String key, String value, int ttlMinutes) {
        redisTemplate.opsForValue().set(key, value, ttlMinutes, TimeUnit.MINUTES);
    }

    public String getString(String key) {
        return redisTemplate.opsForValue().get(key);
    }

    // --- OTP brute-force limiting -------------------------------------------
    //
    // A 6-digit code is ~20 bits. Within its 5-minute window an unthrottled attacker
    // walks the whole space, so the TTL alone is not a control — the attempt cap is what
    // makes the code strong enough to use. Counters live in Redis rather than in memory
    // because the services run multi-replica behind the gateway; a per-instance counter
    // would let an attacker get the cap's worth of guesses per replica.

    /** Max wrong guesses per identifier before its code is burned. */
    public static final int MAX_OTP_ATTEMPTS = 5;

    /** Max codes that may be requested for one identifier per window. */
    public static final int MAX_OTP_REQUESTS = 5;

    private static final int OTP_ATTEMPT_WINDOW_SECONDS = 900;

    /**
     * Records a failed guess.
     *
     * @return true when the caller should now treat the code as burned.
     */
    public boolean registerOtpFailure(String scope, String identifier) {
        String key = "OTP_FAIL:" + scope + ":" + identifier;
        Long attempts = redisTemplate.opsForValue().increment(key);
        if (attempts != null && attempts == 1L) {
            // Only set expiry on first increment, otherwise a steady drip of guesses
            // would keep pushing the window out and the counter would never reset.
            redisTemplate.expire(key, OTP_ATTEMPT_WINDOW_SECONDS, TimeUnit.SECONDS);
        }
        return attempts != null && attempts >= MAX_OTP_ATTEMPTS;
    }

    public boolean isOtpLocked(String scope, String identifier) {
        String value = redisTemplate.opsForValue().get("OTP_FAIL:" + scope + ":" + identifier);
        if (value == null) {
            return false;
        }
        try {
            return Long.parseLong(value) >= MAX_OTP_ATTEMPTS;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public void clearOtpFailures(String scope, String identifier) {
        redisTemplate.delete("OTP_FAIL:" + scope + ":" + identifier);
    }

    /**
     * Rate-limits code issuance, so the send endpoint cannot be used to spray a user's
     * inbox or to keep minting fresh codes until one is guessed.
     *
     * @return true when this request is over the limit and should be dropped.
     */
    public boolean registerOtpRequestAndCheckLimit(String scope, String identifier) {
        String key = "OTP_REQ:" + scope + ":" + identifier;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, OTP_ATTEMPT_WINDOW_SECONDS, TimeUnit.SECONDS);
        }
        return count != null && count > MAX_OTP_REQUESTS;
    }

}