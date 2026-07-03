package com.xammer.billops.security;

import com.xammer.cloud.security.ClientUserDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Blocks billops API access for clients whose trial has expired.
 *
 * Trial status is owned by xamops-service and published to Redis under
 * "trial:status:{clientId}" (the same Redis used for shared sessions). Reading
 * that key here keeps enforcement consistent across services.
 *
 * Admin trial-management endpoints stay reachable so admins can extend/convert
 * even for expired clients. On a cache miss we fail open — xamops-service is the
 * authoritative enforcement point and keeps the key warm.
 */
@Component
public class TrialEnforcementFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TrialEnforcementFilter.class);
    private static final String CACHE_PREFIX = "trial:status:";
    private static final String STATUS_EXPIRED = "EXPIRED";

    private final StringRedisTemplate redisTemplate;

    public TrialEnforcementFilter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/")) {
            return true;
        }
        // Keep admin trial management + login/logout reachable.
        if (path.startsWith("/api/billops/admin/trials") || path.equals("/logout")) {
            return true;
        }
        return false;
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
                    log.warn("[BillOps] Trial status lookup failed for client {}: {}", clientId, e.getMessage());
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
