package com.xammer.billops.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.billops.dto.GlobalUserDto;
import com.xammer.billops.service.MasterDatabaseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Component
public class CustomAuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MasterDatabaseService masterDatabaseService;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException, ServletException {

        response.setStatus(HttpStatus.OK.value());
        response.setContentType("application/json");

        // 1. Determine Redirect URL based on Role
        String redirectUrl = "/dashboard.html";
        Collection<? extends GrantedAuthority> authorities = authentication.getAuthorities();
        for (GrantedAuthority authority : authorities) {
            if (authority.getAuthority().equals("ROLE_BILLOPS_ADMIN")) {
                redirectUrl = "/billops/admin_invoices.html";
                break;
            } else if (authority.getAuthority().equals("ROLE_BILLOPS")) {
                redirectUrl = "/billops/billing.html";
                break;
            }
        }

        // 2. CRITICAL FIX: Fetch Tenant ID to send back to Frontend
        String username = authentication.getName();
        String tenantId = "default"; // Default fallback

        try {
            Optional<GlobalUserDto> globalUser = masterDatabaseService.findGlobalUser(username);
            if (globalUser.isPresent()) {
                tenantId = globalUser.get().getTenantId();
            }
        } catch (Exception e) {
            // Log error but continue login
            System.err.println("Error fetching tenant for user " + username + ": " + e.getMessage());
        }

        // 3. Build Response
        Map<String, String> data = new HashMap<>();
        data.put("redirectUrl", redirectUrl);
        data.put("tenantId", tenantId); // <--- Sending this allows Frontend to set Context
        data.put("username", username);

        response.getWriter().write(objectMapper.writeValueAsString(data));
    }
}