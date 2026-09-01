package com.xammer.cloud.service.auth;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.FlexComputeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Creates an authenticated Spring Security session for a user and computes the
 * role-based redirect target. Extracted from LoginOtpController so the OTP flow
 * and the Google login flow share a single, consistent implementation.
 */
@Service
@Slf4j
public class SessionService {

    @Autowired
    private UserDetailsService userDetailsService;

    @Autowired
    private CloudAccountRepository cloudAccountRepository;

    @Autowired
    private FlexComputeService flexComputeService;

    @Value("${app.frontend.url}")
    private String frontendUrl;

    /**
     * Establishes an authenticated session for the given user (session fixation safe)
     * and returns { redirectUrl, tenantId }.
     */
    public Map<String, String> establishSession(User user, HttpServletRequest request) {
        UserDetails userDetails = userDetailsService.loadUserByUsername(user.getUsername());

        UsernamePasswordAuthenticationToken authToken =
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(authToken);
        SecurityContextHolder.setContext(securityContext);

        // Invalidate any existing session to prevent session fixation when switching users.
        HttpSession existingSession = request.getSession(false);
        if (existingSession != null) {
            existingSession.invalidate();
        }
        HttpSession session = request.getSession(true);
        session.setAttribute("SPRING_SECURITY_CONTEXT", securityContext);

        ClientUserDetails clientDetails = (ClientUserDetails) userDetails;
        Set<String> roles = userDetails.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .collect(Collectors.toSet());

        Long clientId = clientDetails.getClientId();
        List<CloudAccount> accounts;
        try {
            accounts = clientId != null ? cloudAccountRepository.findByClientId(clientId) : Collections.emptyList();
        } catch (Exception e) {
            log.error("Failed to fetch accounts for clientId {}: {}", clientId, e.getMessage());
            accounts = Collections.emptyList();
        }

        String targetUrl;
        if (roles.contains("ROLE_BILLOPS_ADMIN")) {
            targetUrl = frontendUrl + "/billops-admin-dashboard";
        } else if (roles.contains("ROLE_BILLOPS")) {
            targetUrl = frontendUrl + "/billops-dashboard";
        } else if (roles.contains("ROLE_XAMOPS")) {
            targetUrl = frontendUrl + "/unified-dashboard";
        } else if (accounts.isEmpty()) {
            targetUrl = frontendUrl + "/account-manager";
        } else {
            targetUrl = frontendUrl + "/unified-dashboard";
        }

        String tenantId = clientDetails.getTenantId() != null ? clientDetails.getTenantId() : "default";

        // Optional: auto-authenticate with CloudiDR (non-fatal)
        try {
            flexComputeService.isAuthenticated(clientDetails.getId());
        } catch (Exception e) {
            log.warn("CloudiDR auto-authentication failed for user {}: {}", clientDetails.getId(), e.getMessage());
        }

        return Map.of("redirectUrl", targetUrl, "tenantId", tenantId);
    }
}
