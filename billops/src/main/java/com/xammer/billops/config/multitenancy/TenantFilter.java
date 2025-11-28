package com.xammer.billops.config.multitenancy;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.core.Ordered;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE) // Ensure this runs before Spring Security
public class TenantFilter implements Filter {

    private static final String TENANT_HEADER = "X-Tenant-ID";
    private static final String TENANT_PARAM = "tenantId"; // New Parameter Key

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest req = (HttpServletRequest) request;
        
        // 1. Try getting Tenant ID from Header (Used by API calls / Sidebar)
        String tenantId = req.getHeader(TENANT_HEADER);

        // 2. If Header is missing, try getting from Request Parameter (Used by Login Form)
        if (tenantId == null || tenantId.isEmpty()) {
            tenantId = request.getParameter(TENANT_PARAM);
        }

        // 3. If found, set context for this thread
        if (tenantId != null && !tenantId.isEmpty()) {
            // System.out.println("Switching to Tenant: " + tenantId); // Debug Log
            TenantContext.setCurrentTenant(tenantId);
        }

        try {
            chain.doFilter(request, response);
        } finally {
            // 4. ALWAYS Clear context to prevent memory leaks
            TenantContext.clear();
        }
    }
}