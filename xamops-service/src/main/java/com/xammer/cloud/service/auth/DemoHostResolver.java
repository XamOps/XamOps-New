package com.xammer.cloud.service.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Single source of truth for "is this request arriving on the demo host?" so that the Google and
 * Microsoft OAuth controllers cannot diverge on demo-host detection.
 *
 * <p>The demo host is configured via {@code app.demo.host} (blank outside prod). When that property
 * is blank, no request is ever treated as a demo host — the local/dev/test fail-safe.
 *
 * <p>Detection reads the {@code Host} header only (not {@code X-Forwarded-Host}): on the OAuth
 * callback path the backend receives {@code Host} preserved end-to-end by both nginx hops, while
 * {@code X-Forwarded-Host} is never forwarded. Reading {@code Host} alone is both correct and narrows
 * the forwarded-header trust surface.
 */
@Component
public class DemoHostResolver {

    private final String demoHost;

    public DemoHostResolver(@Value("${app.demo.host:}") String demoHost) {
        this.demoHost = demoHost == null ? "" : demoHost.trim().toLowerCase();
    }

    /**
     * @return true only when {@code app.demo.host} is configured (non-blank) and the request's
     *         {@code Host} header (minus any {@code :port} suffix) matches it, case-insensitively.
     */
    public boolean isDemoHost(HttpServletRequest request) {
        if (demoHost.isBlank()) {
            return false; // local/dev fail-safe: nothing is a demo host when unconfigured.
        }
        if (request == null) {
            return false;
        }
        String host = request.getHeader("Host");
        if (host == null || host.isBlank()) {
            return false;
        }
        host = host.trim().toLowerCase();
        int colon = host.indexOf(':');
        if (colon >= 0) {
            host = host.substring(0, colon);
        }
        return host.equals(demoHost);
    }
}
