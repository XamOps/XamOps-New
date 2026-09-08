package com.xammer.cloud.config;

import com.xammer.cloud.service.cur.CostSourceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Reads the {@code costSource} request parameter and makes it available to the cost
 * services for the duration of the request.
 *
 * <p>A filter rather than per-controller wiring because the UI's Refresh button fans out
 * to a dozen cost endpoints across three pages. Handling it centrally means a new cost
 * endpoint honours the preference automatically, instead of working correctly only if
 * whoever added it remembered to plumb a parameter through.
 *
 * <p>Only {@code costSource=CE} does anything; everything else falls through to the
 * default of preferring the Cost and Usage Report.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class CostSourceFilter extends OncePerRequestFilter {

    private static final String PARAM = "costSource";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        CostSourceContext preference = CostSourceContext.parse(request.getParameter(PARAM));
        try {
            if (preference != CostSourceContext.AUTO) {
                CostSourceContext.set(preference);
            }
            chain.doFilter(request, response);
        } finally {
            // Servlet threads are pooled. A value left behind here would silently send an
            // unrelated later request to the wrong source, which is close to impossible to
            // reproduce — so it is cleared unconditionally, not only when it was set.
            CostSourceContext.clear();
        }
    }

    /**
     * Skips paths that cannot be cost queries, so the common case does not pay for a
     * parameter read on every static asset and health check.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/");
    }
}
