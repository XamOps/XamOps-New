package com.xammer.billops.config.multitenancy;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
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
            try {
                ImpersonationContext.setImpersonatedUserId(Long.parseLong(impersonateId));
            } catch (NumberFormatException e) {
                // Ignore invalid headers
            }
        }

        try {
            chain.doFilter(request, response);
        } finally {
            ImpersonationContext.clear();
        }
    }
}