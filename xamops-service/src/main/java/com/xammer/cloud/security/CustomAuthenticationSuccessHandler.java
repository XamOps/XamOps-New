package com.xammer.cloud.security;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.UserActivityEvent;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.service.FlexComputeService;
import com.xammer.cloud.service.UserActivityService;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class CustomAuthenticationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private static final Logger logger = LoggerFactory.getLogger(CustomAuthenticationSuccessHandler.class);

    private final CloudAccountRepository cloudAccountRepository;
    private final FlexComputeService flexComputeService;
    private final UserRepository userRepository;
    private final UserActivityService userActivityService;
    private final String frontendUrl;

    public CustomAuthenticationSuccessHandler(CloudAccountRepository cloudAccountRepository,
            FlexComputeService flexComputeService,
            UserRepository userRepository,
            UserActivityService userActivityService,
            @Value("${app.frontend.url}") String frontendUrl) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.flexComputeService = flexComputeService;
        this.userRepository = userRepository;
        this.userActivityService = userActivityService;
        this.frontendUrl = frontendUrl;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException, ServletException {
        ClientUserDetails userDetails = (ClientUserDetails) authentication.getPrincipal();

        // Record last login time, plus an append-only event carrying the detail the
        // single last_login_at column cannot hold (provider, address, session).
        try {
            userRepository.findById(userDetails.getId()).ifPresent(user -> {
                user.setLastLoginAt(LocalDateTime.now());
                userRepository.save(user);
                userActivityService.recordLogin(user, UserActivityEvent.PROVIDER_LOCAL, request);
            });
        } catch (Exception e) {
            logger.warn("Failed to record last login time for user {}: {}", userDetails.getId(), e.getMessage());
        }

        Set<String> roles = userDetails.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());

        String targetUrl;
        Long clientId = userDetails.getClientId();

        List<CloudAccount> accounts;
        try {
            accounts = clientId != null ? cloudAccountRepository.findByClientId(clientId) : Collections.emptyList();
        } catch (Exception e) {
            logger.error("Failed to fetch accounts for clientId {}: {}", clientId, e.getMessage());
            accounts = Collections.emptyList();
        }

        // 1. Determine Redirect URL based on Roles and Accounts
        if (roles.contains("ROLE_BILLOPS_ADMIN")) {
            // BillOps Admin users go to admin dashboard
            targetUrl = frontendUrl + "/billops-admin-dashboard";
        } else if (roles.contains("ROLE_BILLOPS")) {
            // BillOps regular users go to user dashboard
            targetUrl = frontendUrl + "/billops-dashboard";
        } else if (roles.contains("ROLE_XAMOPS")) {
            // XamOps users go to unified dashboard
            targetUrl = frontendUrl + "/unified-dashboard";
        } else if (accounts.isEmpty()) {
            // Users with no accounts go to account manager
            targetUrl = frontendUrl + "/account-manager";
        } else {
            // Default fallback for other users with accounts
            targetUrl = frontendUrl + "/unified-dashboard.html";
        }

        // 2. Get Tenant ID from authenticated principal (already resolved during authentication)
        String tenantId = userDetails.getTenantId() != null ? userDetails.getTenantId() : "default";

        logger.debug("Authentication successful. Redirect: {}, Tenant: {}", targetUrl, tenantId);

        // 3. Auto-authenticate with CloudiDR using configured credentials
        try {
            Long userId = userDetails.getId();
            logger.info("Auto-authenticating user {} with CloudiDR after XamOps login", userId);

            // This will automatically use cloudidr.auth.email and cloudidr.auth.password from properties
            flexComputeService.isAuthenticated(userId);
            logger.info("CloudiDR auto-authentication completed for user {}", userId);
        } catch (Exception e) {
            logger.warn("CloudiDR auto-authentication failed for user {}: {}", userDetails.getId(), e.getMessage());
            // Don't fail the XamOps login if CloudiDR auth fails
        }

        // 4. Write JSON Response
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        // Sending both redirectUrl AND tenantId
        response.getWriter().write(String.format("{\"redirectUrl\":\"%s\", \"tenantId\":\"%s\"}", targetUrl, tenantId));
    }
}