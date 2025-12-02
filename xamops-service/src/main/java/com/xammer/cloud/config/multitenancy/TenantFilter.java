package com.xammer.cloud.config.multitenancy;

import com.xammer.cloud.dto.GlobalUserDto;
import com.xammer.cloud.service.MasterDatabaseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Component;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Optional;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE) // Ensure this runs before Spring Security
public class TenantFilter implements Filter {

    private static final String TENANT_HEADER = "X-Tenant-ID";
    private static final String TENANT_PARAM = "tenantId";
    // Standard Spring Security session attribute key
    private static final String SPRING_SECURITY_CONTEXT_KEY = "SPRING_SECURITY_CONTEXT";

    @Autowired
    private MasterDatabaseService masterDatabaseService;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest req = (HttpServletRequest) request;

        // 1. Try getting Tenant ID from Header (Used by API calls / Sidebar)
        String tenantId = req.getHeader(TENANT_HEADER);

        // 2. If Header is missing, try getting from Request Parameter (Used by Login
        // Form)
        if (tenantId == null || tenantId.isEmpty()) {
            tenantId = request.getParameter(TENANT_PARAM);
        }

        // 3. FALLBACK: If Header/Param missing, try resolving from Session
        // This fixes the issue where dashboard reloads fail because headers are lost
        if (tenantId == null || tenantId.isEmpty()) {
            tenantId = resolveTenantFromSession(req);
        }

        // 4. If found, set context for this thread
        if (tenantId != null && !tenantId.isEmpty()) {
            TenantContext.setCurrentTenant(tenantId);
        }

        try {
            chain.doFilter(request, response);
        } finally {
            // 5. ALWAYS Clear context to prevent memory leaks
            TenantContext.clear();
        }
    }

    /**
     * Helper to extract the username from the Spring Security session and look up
     * the tenant.
     * This works even if the filter runs before the Security Chain filters because
     * we access the raw session.
     */
    private String resolveTenantFromSession(HttpServletRequest req) {
        try {
            HttpSession session = req.getSession(false);
            if (session != null) {
                Object securityContextObj = session.getAttribute(SPRING_SECURITY_CONTEXT_KEY);
                if (securityContextObj instanceof SecurityContext) {
                    SecurityContext securityContext = (SecurityContext) securityContextObj;
                    Authentication auth = securityContext.getAuthentication();

                    if (auth != null && auth.isAuthenticated() && auth.getName() != null) {
                        String username = auth.getName();
                        // Query Master DB to find where this user belongs
                        Optional<GlobalUserDto> userDto = masterDatabaseService.findGlobalUser(username);
                        if (userDto.isPresent()) {
                            return userDto.get().getTenantId();
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Log silently to avoid flooding logs on unauthenticated requests
            // System.err.println("TenantFilter fallback failed: " + e.getMessage());
        }
        return null;
    }
}