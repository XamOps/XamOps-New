package com.xammer.cloud.config.multitenancy;

import com.xammer.cloud.dto.GlobalUserDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Component;

import org.slf4j.MDC;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Optional;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE) // Ensure this runs before Spring Security
public class TenantFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(TenantFilter.class);

    private static final String TENANT_HEADER = "X-Tenant-ID";
    private static final String TENANT_PARAM = "tenantId";
    // Standard Spring Security session attribute key
    private static final String SPRING_SECURITY_CONTEXT_KEY = "SPRING_SECURITY_CONTEXT";

    @Autowired
    private com.xammer.cloud.service.MasterDatabaseService masterDatabaseService;

    @Autowired
    private com.xammer.cloud.service.EnvironmentService environmentService;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest req = (HttpServletRequest) request;
        String env = environmentService.getCurrentEnvironment();

        // Single-tenant mode: always use "system" tenant for all environments
        TenantContext.setCurrentTenant("system");

        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            MDC.remove("tenant");
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
                            String tenantId = userDto.get().getTenantId();
                            log.debug("TenantFilter: Resolved tenant {} for user {}", tenantId, username);
                            return tenantId;
                        } else {
                            log.debug("TenantFilter: User {} not found in global directory", username);
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Log silently to avoid flooding logs on unauthenticated requests
            log.warn("TenantFilter fallback failed: {}", e.getMessage());
        }
        return null;
    }
}
