package com.xammer.cloud.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Per-caller-IP fixed-window limiter for the public connect endpoints (design §4.3): 20 requests
 * per minute, same Redis fixed-window pattern as {@code RateLimitInterceptor}. It is
 * <em>fail-open</em> — a Redis outage never blocks a connect — which is acceptable because the
 * 256-bit token makes guessing infeasible.
 */
@Component
public class ConnectRateLimiter {

    private static final Logger logger = LoggerFactory.getLogger(ConnectRateLimiter.class);

    static final int MAX_REQUESTS_PER_MINUTE = 20;
    private static final Duration WINDOW = Duration.ofSeconds(60);
    private static final String KEY_PREFIX = "ratelimit:connect:";

    private final StringRedisTemplate redisTemplate;

    public ConnectRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** @return true when the caller may proceed, false when over the limit. Fail-open on Redis errors. */
    public boolean allow(String callerIp) {
        String ip = (callerIp == null || callerIp.isBlank()) ? "unknown" : callerIp;
        long windowBucket = Instant.now().getEpochSecond() / WINDOW.getSeconds();
        String key = KEY_PREFIX + ip + ":" + windowBucket;
        long count;
        try {
            Long incremented = redisTemplate.opsForValue().increment(key);
            count = incremented != null ? incremented : 1L;
            if (count == 1L) {
                redisTemplate.expire(key, WINDOW);
            }
        } catch (Exception e) {
            logger.debug("Connect rate limit check skipped (store unavailable): {}", e.getMessage());
            return true;
        }
        if (count > MAX_REQUESTS_PER_MINUTE) {
            logger.warn("Connect rate limit exceeded for ip-bucket (requests={})", count);
            return false;
        }
        return true;
    }
}
