package com.xammer.cloud.permissions.autospotting;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * The AutoSpotting CSRF and origin filter (design-core.md §11 "CSRF", DR-30, FR-SEC-01, AC-SEC-01).
 *
 * <p>Global CSRF is disabled in both xamops-service filter chains and the session cookie is
 * {@code SameSite=None}, so AutoSpotting defends its own mutating endpoints rather than waiting for
 * the global fix. On every <em>non-GET</em> request to one of the configured path prefixes this
 * filter requires:
 * <ul>
 *   <li>{@code Content-Type: application/json} (a cross-site HTML form can't set it), and the custom
 *       header {@code X-XamOps-Intent: autospotting} (a cross-site form can't set a custom header;
 *       a cross-origin XHR that tried would trigger a CORS preflight the explicit origin list
 *       rejects). A request missing either is answered {@code 403 CSRF_HEADER_REQUIRED};</li>
 *   <li>an {@code Origin}, when present, inside {@code autospotting.security.allowed-origins}, and
 *       no {@code Sec-Fetch-Site: cross-site}. A request failing either is answered
 *       {@code 403 CSRF_ORIGIN_REJECTED}.</li>
 * </ul>
 * The header check runs first, then the origin check. GET, HEAD, OPTIONS and TRACE are never gated
 * (reads and the CORS preflight pass through), and any request outside the configured prefixes is
 * untouched, so non-AutoSpotting traffic is unaffected (FR-CMP-05).
 *
 * <p>This filter lives in {@code permissions-common} so xamops-service registers it now and billops
 * reuses the same class in Phase 34 (its fee endpoints, ledger §10.4). Each service supplies its own
 * prefix list and allowed-origin list; the module itself still serves no HTTP (the Servlet API is a
 * provided-scope dependency).
 *
 * <p><strong>Fail-safe construction (design-core.md §11).</strong> The allowed-origin list is an
 * explicit per-environment list with no wildcards: the constructor throws if any entry contains
 * {@code *} or a tunnel domain ({@code ngrok}), so a profile that weakened the list fails startup
 * rather than silently letting a tunnel page send credentialed mutations. The header check does not
 * rely on CORS being configured correctly; the origin check is an independent second gate.
 */
public class AutoSpottingIntentFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AutoSpottingIntentFilter.class);

    /** The custom intent header a cross-site form cannot set (design-core.md §11). */
    public static final String INTENT_HEADER = "X-XamOps-Intent";

    /** The required value of {@link #INTENT_HEADER}. */
    public static final String INTENT_VALUE = "autospotting";

    /** Stable error code for a missing {@code Content-Type}/intent header (AC-SEC-01). */
    public static final String CSRF_HEADER_REQUIRED = "CSRF_HEADER_REQUIRED";

    /** Stable error code for a rejected {@code Origin}/{@code Sec-Fetch-Site} (design-core.md §11). */
    public static final String CSRF_ORIGIN_REJECTED = "CSRF_ORIGIN_REJECTED";

    /** Tunnel-domain substrings that may never appear in an allowed origin (design-core.md §11). */
    private static final List<String> TUNNEL_DOMAINS = List.of("ngrok");

    private final List<String> gatedPrefixes;
    private final List<String> allowedOrigins;

    /**
     * @param gatedPrefixes  the non-GET path prefixes this filter gates (exact servlet-path prefixes,
     *                       e.g. {@code /api/autospotting/v2/})
     * @param allowedOrigins the explicit {@code Origin} values allowed on a gated non-GET request;
     *                       no wildcards and no tunnel domains (checked at startup)
     * @throws IllegalStateException if an allowed origin contains {@code *} or a tunnel domain
     */
    public AutoSpottingIntentFilter(List<String> gatedPrefixes, List<String> allowedOrigins) {
        this.gatedPrefixes = gatedPrefixes == null ? List.of() : List.copyOf(gatedPrefixes);
        this.allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        for (String origin : this.allowedOrigins) {
            String lower = origin == null ? "" : origin.toLowerCase(Locale.ROOT);
            if (lower.contains("*")) {
                throw new IllegalStateException(
                        "autospotting.security.allowed-origins must not contain a wildcard: " + origin);
            }
            for (String tunnel : TUNNEL_DOMAINS) {
                if (lower.contains(tunnel)) {
                    throw new IllegalStateException(
                            "autospotting.security.allowed-origins must not contain a tunnel domain ("
                                    + tunnel + "): " + origin);
                }
            }
        }
        log.info("AutoSpotting intent filter: {} gated prefix(es), {} allowed origin(s)",
                this.gatedPrefixes.size(), this.allowedOrigins.size());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !isGatedMutation(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // Header gate first (design-core.md §11): Content-Type application/json and the intent header.
        if (!isJsonContentType(request.getContentType()) || !INTENT_VALUE.equals(request.getHeader(INTENT_HEADER))) {
            reject(request, response, CSRF_HEADER_REQUIRED, "AutoSpotting requests require the "
                    + INTENT_HEADER + ": " + INTENT_VALUE + " header and a JSON content type.");
            return;
        }
        // Origin gate second: an Origin outside the list, or an explicit cross-site fetch, is rejected.
        String origin = request.getHeader("Origin");
        if (origin != null && !allowedOrigins.contains(origin)) {
            reject(request, response, CSRF_ORIGIN_REJECTED, "The request Origin is not allowed.");
            return;
        }
        if ("cross-site".equalsIgnoreCase(request.getHeader("Sec-Fetch-Site"))) {
            reject(request, response, CSRF_ORIGIN_REJECTED, "Cross-site requests are not allowed.");
            return;
        }
        filterChain.doFilter(request, response);
    }

    /** True for a non-GET request whose servlet path is under one of the gated prefixes. */
    private boolean isGatedMutation(HttpServletRequest request) {
        String method = request.getMethod();
        if (method == null) {
            return false;
        }
        String upper = method.toUpperCase(Locale.ROOT);
        if (upper.equals("GET") || upper.equals("HEAD") || upper.equals("OPTIONS") || upper.equals("TRACE")) {
            return false;
        }
        String path = request.getServletPath();
        if (path == null || path.isEmpty()) {
            path = request.getRequestURI();
        }
        if (path == null) {
            return false;
        }
        for (String prefix : gatedPrefixes) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isJsonContentType(String contentType) {
        if (contentType == null) {
            return false;
        }
        String lower = contentType.toLowerCase(Locale.ROOT).trim();
        // Accept application/json and application/json;charset=... (but not text/plain, form posts, etc.).
        return lower.equals("application/json") || lower.startsWith("application/json;");
    }

    /** Writes the RFC 9457 {@code application/problem+json} body with the stable {@code code}. */
    private void reject(HttpServletRequest request, HttpServletResponse response, String code, String detail)
            throws IOException {
        log.warn("AutoSpotting intent filter rejected {} {}: {}", request.getMethod(),
                request.getRequestURI(), code);
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        String body = "{\"type\":\"https://xamops.com/errors/as/" + code + "\","
                + "\"title\":\"Forbidden\","
                + "\"status\":403,"
                + "\"code\":\"" + code + "\","
                + "\"detail\":\"" + escapeJson(detail) + "\","
                + "\"requestId\":null,\"operationId\":null,\"switch\":null,"
                + "\"retryable\":false,\"fieldErrors\":[]}";
        response.getWriter().write(body);
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
