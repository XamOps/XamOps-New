package com.xammer.cloud.security;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
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

@Component
public class CustomAuthenticationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

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
        String role = userDetails.getAuthorities().stream()
                .findFirst()
                .map(GrantedAuthority::getAuthority)
                .orElse("");

        String targetUrl;

        // Redirect BILLOPS users directly to their specific page
        if ("ROLE_BILLOPS".equals(role)) {
            targetUrl = frontendUrl + "/billops/billing";
        } else { // Handle ADMIN and XAMOPS users
            Long clientId = userDetails.getClientId();
            List<CloudAccount> accounts = cloudAccountRepository.findByClientId(clientId);

            if (accounts.isEmpty()) {
                // Default to the main dashboard if no accounts are connected
                targetUrl = frontendUrl + "/dashboard.html";
            } else {
                // Redirect to the dashboard corresponding to the first connected account
                CloudAccount defaultAccount = accounts.get(0);
                String firstAccountId = "AWS".equals(defaultAccount.getProvider())
                        ? defaultAccount.getAwsAccountId()
                        : defaultAccount.getGcpProjectId();

                String dashboardPage = "GCP".equals(defaultAccount.getProvider())
                        ? "/gcp_dashboard.html"
                        : "/dashboard.html";

                targetUrl = frontendUrl + dashboardPage + "?accountId=" + firstAccountId;
            }
        }

        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }
}