package com.xammer.cloud.security;

import com.xammer.cloud.service.auth.TrialService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
 * Blocks API access for clients whose trial has expired. Runs after
 * authentication so the principal (and its clientId) is available.
 *
 * Exemptions let an expired user still reach the "expired" screen and take
 * action: everything under /api/auth/** (trial status, contact sales, feedback,
 * logout-adjacent) plus non-API paths pass through untouched.
 *
 * Fail-safe: an EXPIRED status always blocks; a Redis cache miss falls back to a
 * DB read inside TrialService before allowing access.
 */
@Component
@Slf4j
public class TrialEnforcementFilter extends OncePerRequestFilter {

    @Autowired
    private TrialService trialService;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // Only guard API calls; let static assets / SPA routes through.
        if (!path.startsWith("/api/")) {
            return true;
        }
        // Auth, trial status, contact sales, feedback, logout must stay reachable.
        if (path.startsWith("/api/auth/") || path.equals("/logout")) {
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
            String status = trialService.statusForClient(clientId);
            if (TrialService.STATUS_EXPIRED.equals(status)) {
                response.setStatus(HttpStatus.PAYMENT_REQUIRED.value()); // 402
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"TRIAL_EXPIRED\",\"message\":\"Your free trial has ended. Please contact sales to continue.\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
