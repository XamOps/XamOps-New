package com.xammer.cloud.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.time.Instant;

/**
 * Fixed-window per-user rate limiter for the DevOps script API.
 *
 * <p>Allows at most {@value #MAX_REQUESTS_PER_MINUTE} requests per user per
 * rolling one-minute window. Counters are kept in Redis (keyed by user +
 * minute bucket) so the limit holds across horizontally-scaled instances.</p>
 *
 * <p>The limiter is <em>fail-open</em>: if Redis is unavailable the request is
 * allowed through, so a cache outage never takes the API down.</p>
 *
 * Requirements: REQ-2.8.1 (Authentication &amp; Authorization — rate limiting),
 *               REQ-2.8.2 (Input Validation / abuse prevention)
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(RateLimitInterceptor.class);

    static final int MAX_REQUESTS_PER_MINUTE = 100;
    private static final Duration WINDOW = Duration.ofSeconds(60);
    private static final String KEY_PREFIX = "ratelimit:devops:";

    private final StringRedisTemplate redisTemplate;

    public RateLimitInterceptor(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {

        String userId = resolveUserId();
        long windowBucket = Instant.now().getEpochSecond() / WINDOW.getSeconds();
        String key = KEY_PREFIX + userId + ":" + windowBucket;

        long count;
        try {
            Long incremented = redisTemplate.opsForValue().increment(key);
            count = incremented != null ? incremented : 1L;
            if (count == 1L) {
                // First hit in this window — set the bucket to expire so it self-cleans.
                redisTemplate.expire(key, WINDOW);
            }
        } catch (Exception e) {
            // Fail open: never block traffic because the rate-limit store is down.
            logger.debug("Rate limit check skipped (store unavailable): {}", e.getMessage());
            return true;
        }

        if (count > MAX_REQUESTS_PER_MINUTE) {
            logger.warn("Rate limit exceeded for user={} ({} requests in window)", userId, count);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader("Retry-After", String.valueOf(WINDOW.getSeconds()));
            response.getWriter().write(
                    "{\"error\":\"Too Many Requests\","
                    + "\"message\":\"Rate limit of " + MAX_REQUESTS_PER_MINUTE
                    + " requests per minute exceeded. Please slow down and try again shortly.\"}");
            return false;
        }

        return true;
    }

    /**
     * Resolve a stable per-caller identity for rate limiting. Falls back to the
     * client IP for unauthenticated/anonymous callers so they are still bounded.
     */
    private String resolveUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getName() != null
                && !"anonymousUser".equals(auth.getName())) {
            return auth.getName();
        }
        return "anonymous";
    }
}
