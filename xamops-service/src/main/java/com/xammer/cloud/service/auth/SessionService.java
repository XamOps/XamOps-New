package com.xammer.cloud.service.auth;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.FlexComputeService;
import com.xammer.cloud.service.UserActivityService;
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

    @Autowired
    private UserActivityService userActivityService;

    @Autowired
    private DemoHostResolver demoHostResolver;

    @Value("${app.frontend.url}")
    private String frontendUrl;

    /**
     * Establishes an authenticated session for the given user (session fixation safe)
     * and returns { redirectUrl, tenantId }.
     *
     * @deprecated prefer {@link #establishSession(User, HttpServletRequest, String)} so the
     *             activity trail records which mechanism was actually used. This overload
     *             records the login with an unknown provider.
     */
    @Deprecated
    public Map<String, String> establishSession(User user, HttpServletRequest request) {
        return establishSession(user, request, null);
    }

    /**
     * Establishes an authenticated session and records the login against {@code authProvider}.
     *
     * The provider is passed in rather than read from {@code user.getAuthProvider()} because
     * those are different facts: the column is the account's configured default, while this
     * is the mechanism used for this particular sign-in. A Google-provisioned user signing in
     * by email code is exactly the discrepancy the activity window exists to show.
     *
     * @param authProvider one of the {@code UserActivityEvent.PROVIDER_*} constants
     */
    public Map<String, String> establishSession(User user, HttpServletRequest request,
                                                String authProvider) {
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

        // After the new session exists, so the event carries the id that the eventual logout
        // will also carry. Recording before the fixation handling above would stamp the id of
        // the session just invalidated and the pair could never be matched.
        userActivityService.recordLogin(user, authProvider, request);

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

        // Host-aware base: demo frontend url on the demo host, else the live/per-profile url.
        // On every non-demo host this is byte-identical to frontendUrl, so live behavior is unchanged.
        String base = demoHostResolver.resolveFrontendUrl(request, frontendUrl);

        String targetUrl;
        if (roles.contains("ROLE_BILLOPS_ADMIN")) {
            targetUrl = base + "/billops-admin-dashboard";
        } else if (roles.contains("ROLE_BILLOPS")) {
            targetUrl = base + "/billops-dashboard";
        } else if (roles.contains("ROLE_XAMOPS")) {
            targetUrl = base + "/unified-dashboard";
        } else if (accounts.isEmpty()) {
            targetUrl = base + "/account-manager";
        } else {
            targetUrl = base + "/unified-dashboard";
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
