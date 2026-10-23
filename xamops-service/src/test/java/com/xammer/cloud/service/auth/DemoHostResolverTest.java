package com.xammer.cloud.service.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DemoHostResolver}. Covers the blank-property fail-safe (AC-3),
 * exact Host matching with/without a port, non-matching hosts, and case-insensitivity (AC-11).
 */
class DemoHostResolverTest {

    private static MockHttpServletRequest withHost(String host) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (host != null) {
            request.addHeader("Host", host);
        }
        return request;
    }

    private static final String LIVE_FRONTEND = "https://live.xamops.com";
    private static final String DEMO_FRONTEND = "https://demo.xamops.com";
    private static final String LIVE_GOOGLE = "https://live.xamops.com/api/auth/google/callback";
    private static final String DEMO_GOOGLE = "https://demo.xamops.com/api/auth/google/callback";
    private static final String LIVE_MS = "https://live.xamops.com/api/auth/microsoft/callback";
    private static final String DEMO_MS = "https://demo.xamops.com/api/auth/microsoft/callback";

    /** Resolver with the demo host configured and all demo URLs populated (prod-like). */
    private static DemoHostResolver demoConfigured() {
        return new DemoHostResolver("demo.xamops.com", DEMO_FRONTEND, DEMO_GOOGLE, DEMO_MS);
    }

    @Test
    void blankProperty_neverDemoHost_regardlessOfHostHeader() {
        DemoHostResolver resolver = new DemoHostResolver("", "", "", "");
        assertThat(resolver.isDemoHost(withHost("demo.xamops.com"))).isFalse();
        assertThat(resolver.isDemoHost(withHost("anything.com"))).isFalse();
    }

    @Test
    void nullProperty_neverDemoHost() {
        DemoHostResolver resolver = new DemoHostResolver(null, null, null, null);
        assertThat(resolver.isDemoHost(withHost("demo.xamops.com"))).isFalse();
    }

    @Test
    void exactMatch_isDemoHost() {
        DemoHostResolver resolver = demoConfigured();
        assertThat(resolver.isDemoHost(withHost("demo.xamops.com"))).isTrue();
    }

    @Test
    void matchWithPortSuffix_isDemoHost() {
        DemoHostResolver resolver = demoConfigured();
        assertThat(resolver.isDemoHost(withHost("demo.xamops.com:443"))).isTrue();
    }

    @Test
    void caseInsensitiveMatch_isDemoHost() {
        DemoHostResolver resolver = demoConfigured();
        assertThat(resolver.isDemoHost(withHost("DEMO.Xamops.COM"))).isTrue();
        // configured value is normalised too
        DemoHostResolver upperConfigured = new DemoHostResolver("  DEMO.XAMOPS.COM  ", "", "", "");
        assertThat(upperConfigured.isDemoHost(withHost("demo.xamops.com"))).isTrue();
    }

    @Test
    void nonMatchingHost_isNotDemoHost() {
        DemoHostResolver resolver = demoConfigured();
        assertThat(resolver.isDemoHost(withHost("live.xamops.com"))).isFalse();
    }

    @Test
    void missingOrBlankHostHeader_isNotDemoHost() {
        DemoHostResolver resolver = demoConfigured();
        assertThat(resolver.isDemoHost(withHost(null))).isFalse();
        assertThat(resolver.isDemoHost(withHost("   "))).isFalse();
    }

    @Test
    void nullRequest_isNotDemoHost() {
        DemoHostResolver resolver = demoConfigured();
        assertThat(resolver.isDemoHost((HttpServletRequest) null)).isFalse();
    }

    // ── resolveFrontendUrl / resolveGoogleRedirectUri / resolveMicrosoftRedirectUri ──────────────

    @Test
    void resolve_onDemoHostWithDemoUrlsConfigured_returnsDemoValues() {
        DemoHostResolver resolver = demoConfigured();
        assertThat(resolver.resolveFrontendUrl(withHost("demo.xamops.com"), LIVE_FRONTEND)).isEqualTo(DEMO_FRONTEND);
        assertThat(resolver.resolveGoogleRedirectUri(withHost("demo.xamops.com"), LIVE_GOOGLE)).isEqualTo(DEMO_GOOGLE);
        assertThat(resolver.resolveMicrosoftRedirectUri(withHost("demo.xamops.com"), LIVE_MS)).isEqualTo(DEMO_MS);
    }

    @Test
    void resolve_onDemoHostButDemoUrlsBlank_fallsBackToLive() {
        // Demo host configured, but the demo URLs are blank (non-prod profile shape).
        DemoHostResolver resolver = new DemoHostResolver("demo.xamops.com", "", "", "");
        assertThat(resolver.resolveFrontendUrl(withHost("demo.xamops.com"), LIVE_FRONTEND)).isEqualTo(LIVE_FRONTEND);
        assertThat(resolver.resolveGoogleRedirectUri(withHost("demo.xamops.com"), LIVE_GOOGLE)).isEqualTo(LIVE_GOOGLE);
        assertThat(resolver.resolveMicrosoftRedirectUri(withHost("demo.xamops.com"), LIVE_MS)).isEqualTo(LIVE_MS);
    }

    @Test
    void resolve_onNonDemoHost_returnsLiveValues() {
        DemoHostResolver resolver = demoConfigured();
        assertThat(resolver.resolveFrontendUrl(withHost("live.xamops.com"), LIVE_FRONTEND)).isEqualTo(LIVE_FRONTEND);
        assertThat(resolver.resolveGoogleRedirectUri(withHost("live.xamops.com"), LIVE_GOOGLE)).isEqualTo(LIVE_GOOGLE);
        assertThat(resolver.resolveMicrosoftRedirectUri(withHost("live.xamops.com"), LIVE_MS)).isEqualTo(LIVE_MS);
    }
}
