package com.xammer.cloud.security;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
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

    // The constructor is updated to accept the new 'frontendUrl' property
    public CustomAuthenticationSuccessHandler(CloudAccountRepository cloudAccountRepository,
                                            @Value("${app.frontend.url}") String frontendUrl) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.frontendUrl = frontendUrl;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws IOException, ServletException {
        ClientUserDetails userDetails = (ClientUserDetails) authentication.getPrincipal();
        Long clientId = userDetails.getClientId();
        List<CloudAccount> accounts = cloudAccountRepository.findByClientId(clientId);

        String targetUrl;

        if (accounts.isEmpty()) {
            // If the user has no accounts, redirect to the frontend's root URL.
            targetUrl = frontendUrl + "/dashboard.html"; // Redirect to a specific page
        } else {
            // If user has accounts, get the first one and build the redirect URL with its ID.
            CloudAccount defaultAccount = accounts.get(0);
            String firstAccountId = "AWS".equals(defaultAccount.getProvider())
                    ? defaultAccount.getAwsAccountId()
                    : defaultAccount.getGcpProjectId();
            
            // Prepend the frontendUrl to the path
            targetUrl = frontendUrl + "/dashboard.html?accountId=" + firstAccountId;
        }
        
        // Use the RedirectStrategy to send the user to the full frontend URL
        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }
}