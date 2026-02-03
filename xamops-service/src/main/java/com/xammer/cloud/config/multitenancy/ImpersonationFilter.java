package com.xammer.cloud.config.multitenancy;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;
import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.io.IOException;

@Component
@Order(2) // Run AFTER TenantFilter
public class ImpersonationFilter implements Filter {

    public static final String IMPERSONATION_HEADER = "X-Impersonate-User";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest req = (HttpServletRequest) request;
        String impersonateId = req.getHeader(IMPERSONATION_HEADER);

        if (impersonateId != null && !impersonateId.isEmpty()) {
            // SECURITY CHECK: Only allow impersonation if the CURRENT session user is a
            // SUPER_ADMIN
            if (isSuperAdmin(req)) {
                try {
                    ImpersonationContext.setImpersonatedUserId(Long.parseLong(impersonateId));
                } catch (NumberFormatException e) {
                    // Ignore invalid headers
                }
            } else {
                // If not a Super Admin, do NOT apply the impersonation header
            }
        }

        try {
            chain.doFilter(request, response);
        } finally {
            ImpersonationContext.clear();
        }
    }

    /**
     * Helper to verify if the requester has ROLE_SUPER_ADMIN
     */
    private boolean isSuperAdmin(HttpServletRequest req) {
        try {
            HttpSession session = req.getSession(false);
            if (session != null) {
                Object securityContextObj = session.getAttribute("SPRING_SECURITY_CONTEXT");
                if (securityContextObj instanceof SecurityContext) {
                    SecurityContext securityContext = (SecurityContext) securityContextObj;
                    Authentication auth = securityContext.getAuthentication();

                    if (auth != null && auth.isAuthenticated()) {
                        return auth.getAuthorities().stream()
                                .anyMatch(a -> "ROLE_SUPER_ADMIN".equals(a.getAuthority()));
                    }
                }
            }
        } catch (Exception e) {
            // Silently fail authentication check
        }
        return false;
    }
}