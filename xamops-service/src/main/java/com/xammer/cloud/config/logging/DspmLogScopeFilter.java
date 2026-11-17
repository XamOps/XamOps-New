package com.xammer.cloud.config.logging;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Marks {@code /api/dspm/**} requests in the MDC, so {@link DspmQueryStringTurboFilter} can drop every
 * framework DEBUG line they produce (FR-API-6, design 5.11).
 *
 * <p>Ordered first, so the mark covers {@code CommonsRequestLoggingFilter}, the Spring Security chain,
 * the {@code DispatcherServlet} and every Matters call of the request, which all run on this thread.
 * The key is removed in {@code finally}, as {@code CostSourceFilter} clears its context, because servlet
 * threads are pooled.
 *
 * <p>Error dispatches are covered too. Tomcat runs the {@code /error} page after the request's own
 * chain has returned, so the key is gone by then, and the forward keeps the original query string. So
 * this filter also runs on {@code ERROR} dispatches and decides by the URI of the request that failed.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DspmLogScopeFilter extends OncePerRequestFilter {

    private static final String SCOPE_PREFIX = "/api/dspm/";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        MDC.put(DspmQueryStringTurboFilter.MDC_SCOPE_KEY, "1");
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(DspmQueryStringTurboFilter.MDC_SCOPE_KEY);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !scopeUri(request).startsWith(SCOPE_PREFIX);
    }

    /** Runs on {@code /error} dispatches as well, deciding by the original URI. */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    private static String scopeUri(HttpServletRequest request) {
        if (request.getDispatcherType() == DispatcherType.ERROR) {
            // jakarta.servlet.error.request_uri: the URI of the request that failed
            return request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) instanceof String uri ? uri : "";
        }
        return request.getRequestURI();
    }
}
