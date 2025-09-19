package com.xammer.cloud.security;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.slf4j.Logger; // ✅ ADD THIS IMPORT
import org.slf4j.LoggerFactory; // ✅ ADD THIS IMPORT
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
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class CustomAuthenticationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    // ✅ ADD A LOGGER INSTANCE
    private static final Logger logger = LoggerFactory.getLogger(CustomAuthenticationSuccessHandler.class);

    private final CloudAccountRepository cloudAccountRepository;
    private final String frontendUrl;

    public CustomAuthenticationSuccessHandler(CloudAccountRepository cloudAccountRepository,
                                            @Value("${app.frontend.url}") String frontendUrl) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.frontendUrl = frontendUrl;
    }

   @Override
public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws IOException, ServletException {
    ClientUserDetails userDetails = (ClientUserDetails) authentication.getPrincipal();
    Set<String> roles = userDetails.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .collect(Collectors.toSet());

    String targetUrl;
    Long clientId = userDetails.getClientId();
    List<CloudAccount> accounts = cloudAccountRepository.findByClientId(clientId);

    if (roles.contains("ROLE_BILLOPS_ADMIN")) {
        targetUrl = frontendUrl + "/billops/billing.html";
    } else if (accounts.isEmpty()) {
        targetUrl = frontendUrl + "/account-manager.html";
    } else {
        if (roles.contains("ROLE_BILLOPS")) {
            targetUrl = frontendUrl + "/billops/billing.html" + "?accountIds=" + accounts.get(0).getAwsAccountId();
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

    logger.debug("Authentication successful. Sending redirect target URL: {}", targetUrl);

    // ✅ CHANGED: Instead of redirecting, write a JSON response
    response.setContentType("application/json");
    response.setCharacterEncoding("UTF-8");
    response.getWriter().write("{\"redirectUrl\":\"" + targetUrl + "\"}");
}
}