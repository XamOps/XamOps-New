package com.xammer.mediaops.security;

import com.xammer.cloud.security.ClientUserDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Blocks media-ops API access for clients whose trial has expired.
 *
 * Trial status is owned by xamops-service and published to Redis under
 * "trial:status:{clientId}". This service reads that shared key (the same Redis
 * used for session storage), so an expired trial is blocked here too — not just
 * on the main dashboard.
 *
 * The key is kept warm by xamops-service (every dashboard load / API call
 * refreshes it). On a cache miss we fail open (allow) since this service has no
 * direct view of the trial DB; the authoritative block still happens on
 * xamops-service, and EXPIRED is always published when it occurs.
 */
@Component
public class MediaOpsTrialEnforcementFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(MediaOpsTrialEnforcementFilter.class);
    private static final String CACHE_PREFIX = "trial:status:";
    private static final String STATUS_EXPIRED = "EXPIRED";

    private final StringRedisTemplate redisTemplate;

    public MediaOpsTrialEnforcementFilter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // Only guard media-ops API calls; let websockets/health/media playback through.
        return !path.startsWith("/api/media-ops/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof ClientUserDetails) {
            Long clientId = ((ClientUserDetails) auth.getPrincipal()).getClientId();
            if (clientId != null) {
                String status = null;
                try {
                    status = redisTemplate.opsForValue().get(CACHE_PREFIX + clientId);
                } catch (Exception e) {
                    log.warn("[MediaOps] Trial status lookup failed for client {}: {}", clientId, e.getMessage());
                }
                if (STATUS_EXPIRED.equals(status)) {
                    response.setStatus(HttpStatus.PAYMENT_REQUIRED.value());
                    response.setContentType("application/json");
                    response.getWriter().write("{\"error\":\"TRIAL_EXPIRED\",\"message\":\"Your free trial has ended. Please contact sales to continue.\"}");
                    return;
                }
            }
        }
        chain.doFilter(request, response);
    }
}
