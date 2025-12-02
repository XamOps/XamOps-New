package com.xammer.cloud.security;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.GlobalUserDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.MasterDatabaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class CustomAuthenticationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private static final Logger logger = LoggerFactory.getLogger(CustomAuthenticationSuccessHandler.class);

    private final CloudAccountRepository cloudAccountRepository;
    private final MasterDatabaseService masterDatabaseService;
    private final String frontendUrl;

    public CustomAuthenticationSuccessHandler(CloudAccountRepository cloudAccountRepository,
            MasterDatabaseService masterDatabaseService,
            @Value("${app.frontend.url}") String frontendUrl) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.masterDatabaseService = masterDatabaseService;
        this.frontendUrl = frontendUrl;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException, ServletException {
        ClientUserDetails userDetails = (ClientUserDetails) authentication.getPrincipal();
        Set<String> roles = userDetails.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());

        String targetUrl;
        Long clientId = userDetails.getClientId();
        List<CloudAccount> accounts = cloudAccountRepository.findByClientId(clientId);

        // 1. Determine Redirect URL based on Roles and Accounts
        if (roles.contains("ROLE_BILLOPS_ADMIN")) {
            targetUrl = frontendUrl + "/billops/billing.html";
        } else if (accounts.isEmpty()) {
            targetUrl = frontendUrl + "/account-manager.html";
        } else {
            if (roles.contains("ROLE_BILLOPS")) {
                String accountIdParam = accounts.isEmpty() ? "" : "?accountIds=" + accounts.get(0).getAwsAccountId();
                targetUrl = frontendUrl + "/billops/billing.html" + accountIdParam;
            } else {
                CloudAccount defaultAccount = accounts.get(0);
                String firstAccountId = "AWS".equals(defaultAccount.getProvider())
                        ? defaultAccount.getAwsAccountId()
                        : defaultAccount.getGcpProjectId();

                String page = "GCP".equals(defaultAccount.getProvider())
                        ? "/gcp_dashboard.html"
                        : "/dashboard.html";
                targetUrl = frontendUrl + page + "?accountId=" + firstAccountId;
            }
        }

        // 2. Fetch Tenant ID for Frontend Context
        String tenantId = "default";
        try {
            Optional<GlobalUserDto> globalUser = masterDatabaseService.findGlobalUser(userDetails.getUsername());
            if (globalUser.isPresent()) {
                tenantId = globalUser.get().getTenantId();
            }
        } catch (Exception e) {
            logger.error("Failed to retrieve tenantId for user {}", userDetails.getUsername(), e);
        }

        logger.debug("Authentication successful. Redirect: {}, Tenant: {}", targetUrl, tenantId);

        // 3. Write JSON Response
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        // Sending both redirectUrl AND tenantId
        response.getWriter().write(String.format("{\"redirectUrl\":\"%s\", \"tenantId\":\"%s\"}", targetUrl, tenantId));
    }
}