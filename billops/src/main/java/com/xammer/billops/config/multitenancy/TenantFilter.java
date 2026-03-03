package com.xammer.billops.config.multitenancy;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.servlet.*;
import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TenantFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        // Single-DB mode: always route to "system" tenant for all environments
        TenantContext.setCurrentTenant("system");
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}