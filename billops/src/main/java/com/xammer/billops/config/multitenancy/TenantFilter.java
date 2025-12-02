package com.xammer.billops.config.multitenancy;

import com.xammer.billops.dto.GlobalUserDto;
import com.xammer.billops.service.MasterDatabaseService;
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
    private static final String SPRING_SECURITY_CONTEXT_KEY = "SPRING_SECURITY_CONTEXT";

    @Autowired
    private MasterDatabaseService masterDatabaseService;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest req = (HttpServletRequest) request;

        // 1. Try getting Tenant ID from Header
        String tenantId = req.getHeader(TENANT_HEADER);

        // 2. If Header is missing, try getting from Request Parameter
        if (tenantId == null || tenantId.isEmpty()) {
            tenantId = request.getParameter(TENANT_PARAM);
        }

        // 3. FALLBACK: Resolve from Session
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
            // 5. ALWAYS Clear context
            TenantContext.clear();
        }
    }

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
                        Optional<GlobalUserDto> userDto = masterDatabaseService.findGlobalUser(username);
                        if (userDto.isPresent()) {
                            return userDto.get().getTenantId();
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Ignore errors during fallback
        }
        return null;
    }
}