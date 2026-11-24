package com.xammer.cloud.config.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import org.slf4j.MDC;
import org.slf4j.Marker;

/**
 * Drops framework DEBUG/TRACE lines that would print XamLens or Matters query strings or responses
 * (FR-API-6, design 4.11 and 5.11).
 *
 * <p>The platform sets {@code logging.level.org.springframework.web=DEBUG}, and at that level the
 * framework prints inbound and outbound URLs with their query strings ({@code DispatcherServlet},
 * {@code RestTemplate}, {@code CommonsRequestLoggingFilter}, the {@code "ERROR" dispatch} line) and the
 * first 100 characters of each response ({@code Writing [...]}). For XamLens those carry search terms,
 * filter values, datastore names and file paths. Rather than re-level a logger every module shares, this
 * filter denies only those lines:
 * <ul>
 *   <li>every {@code org.springframework.web*} DEBUG/TRACE line while {@link DspmLogScopeFilter} has
 *       marked the thread as handling an {@code /api/dspm/**} request, its Matters calls included
 *       (they run on the request thread), whatever the host;</li>
 *   <li>outside that scope, a framework DEBUG/TRACE line whose text names an {@code /api/dspm/} URL or
 *       {@code matters.ai}, which covers the admin Verify call to Matters.</li>
 * </ul>
 * INFO and above, and every non-framework logger, are untouched. Inside the scope a DENY on an
 * {@code isDebugEnabled()} probe (null format) also lets the framework skip building the line.
 *
 * <p>Registered in {@code logback-spring.xml}; logback creates it by reflection, through the public
 * no-argument constructor.
 */
public final class DspmQueryStringTurboFilter extends TurboFilter {

    /** The MDC key {@link DspmLogScopeFilter} sets for the duration of an {@code /api/dspm/**} request. */
    public static final String MDC_SCOPE_KEY = "xamlens.scope";

    /** Public and argument-free, because logback instantiates the filter from {@code logback-spring.xml}. */
    public DspmQueryStringTurboFilter() {
    }

    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level, String format, Object[] params, Throwable t) {
        if (level.isGreaterOrEqual(Level.INFO) || !logger.getName().startsWith("org.springframework.web")) {
            return FilterReply.NEUTRAL;   // warnings, errors and every non-framework logger are untouched
        }
        if (MDC.get(MDC_SCOPE_KEY) != null) {
            return FilterReply.DENY;      // any framework DEBUG/TRACE line while an XamLens request is handled
        }
        if (format != null && (format.contains("/api/dspm/") || format.contains("matters.ai"))) {
            return FilterReply.DENY;      // XamLens URLs outside the scope; the admin Verify call to Matters
        }
        return FilterReply.NEUTRAL;
    }
}
