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

    @Test
    void blankProperty_neverDemoHost_regardlessOfHostHeader() {
        DemoHostResolver resolver = new DemoHostResolver("");
        assertThat(resolver.isDemoHost(withHost("demo.xamops.com"))).isFalse();
        assertThat(resolver.isDemoHost(withHost("anything.com"))).isFalse();
    }

    @Test
    void nullProperty_neverDemoHost() {
        DemoHostResolver resolver = new DemoHostResolver(null);
        assertThat(resolver.isDemoHost(withHost("demo.xamops.com"))).isFalse();
    }

    @Test
    void exactMatch_isDemoHost() {
        DemoHostResolver resolver = new DemoHostResolver("demo.xamops.com");
        assertThat(resolver.isDemoHost(withHost("demo.xamops.com"))).isTrue();
    }

    @Test
    void matchWithPortSuffix_isDemoHost() {
        DemoHostResolver resolver = new DemoHostResolver("demo.xamops.com");
        assertThat(resolver.isDemoHost(withHost("demo.xamops.com:443"))).isTrue();
    }

    @Test
    void caseInsensitiveMatch_isDemoHost() {
        DemoHostResolver resolver = new DemoHostResolver("demo.xamops.com");
        assertThat(resolver.isDemoHost(withHost("DEMO.Xamops.COM"))).isTrue();
        // configured value is normalised too
        DemoHostResolver upperConfigured = new DemoHostResolver("  DEMO.XAMOPS.COM  ");
        assertThat(upperConfigured.isDemoHost(withHost("demo.xamops.com"))).isTrue();
    }

    @Test
    void nonMatchingHost_isNotDemoHost() {
        DemoHostResolver resolver = new DemoHostResolver("demo.xamops.com");
        assertThat(resolver.isDemoHost(withHost("live.xamops.com"))).isFalse();
    }

    @Test
    void missingOrBlankHostHeader_isNotDemoHost() {
        DemoHostResolver resolver = new DemoHostResolver("demo.xamops.com");
        assertThat(resolver.isDemoHost(withHost(null))).isFalse();
        assertThat(resolver.isDemoHost(withHost("   "))).isFalse();
    }

    @Test
    void nullRequest_isNotDemoHost() {
        DemoHostResolver resolver = new DemoHostResolver("demo.xamops.com");
        assertThat(resolver.isDemoHost((HttpServletRequest) null)).isFalse();
    }
}
