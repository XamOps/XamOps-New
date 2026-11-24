package com.xammer.cloud.autospotting;

import com.xammer.cloud.permissions.autospotting.AutoSpottingIntentFilter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The AutoSpotting CSRF/origin filter (plan-phase-09.md task 9.1, design-core.md §11, AC-SEC-01).
 * Per gated prefix: a non-GET with no header is 403 {@code CSRF_HEADER_REQUIRED} and with it passes;
 * a non-AutoSpotting POST is untouched; a cross-site {@code Sec-Fetch-Site} or a foreign {@code Origin}
 * is 403 {@code CSRF_ORIGIN_REJECTED}; and startup fails if an allowed origin is a wildcard or tunnel.
 */
class AutoSpottingIntentFilterTest {

    private static final List<String> PREFIXES = List.of(
            "/api/autospotting/v2/",
            "/api/xamops/autospotting-admin/",
            "/api/xamops/billops-admin/autospotting/",
            "/api/autospotting/",
            "/api/gcp/autospotting/");

    private static final String ORIGIN = "https://live.xamops.com";

    private AutoSpottingIntentFilter filter() {
        return new AutoSpottingIntentFilter(PREFIXES, List.of(ORIGIN));
    }

    private static MockHttpServletRequest post(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setServletPath(path);
        return request;
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/autospotting/v2/accounts/1/plans",
            "/api/autospotting/v2/accounts/1/commitments/settings/risp-respect",
            "/api/xamops/autospotting-admin/switches/GLOBAL",
            "/api/xamops/billops-admin/autospotting/fees",
            "/api/autospotting/disable",
            "/api/gcp/autospotting/disable" })
    void nonGet_withoutHeader_is403HeaderRequired(String path) throws Exception {
        MockHttpServletRequest request = post(path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter().doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getContentAsString()).contains("CSRF_HEADER_REQUIRED");
        verify(chain, never()).doFilter(request, response);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/autospotting/v2/accounts/1/plans",
            "/api/autospotting/v2/accounts/1/commitments/settings/risp-respect",
            "/api/xamops/autospotting-admin/switches/GLOBAL",
            "/api/xamops/billops-admin/autospotting/fees",
            "/api/autospotting/disable",
            "/api/gcp/autospotting/disable" })
    void nonGet_withHeaderAndJson_passes(String path) throws Exception {
        MockHttpServletRequest request = post(path);
        request.setContentType("application/json");
        request.addHeader(AutoSpottingIntentFilter.INTENT_HEADER, AutoSpottingIntentFilter.INTENT_VALUE);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter().doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void nonGet_withTextPlain_is403() throws Exception {
        MockHttpServletRequest request = post("/api/autospotting/v2/accounts/1/plans");
        request.setContentType("text/plain");
        request.addHeader(AutoSpottingIntentFilter.INTENT_HEADER, AutoSpottingIntentFilter.INTENT_VALUE);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter().doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("CSRF_HEADER_REQUIRED");
    }

    @Test
    void nonAutoSpottingPost_isUntouched() throws Exception {
        MockHttpServletRequest request = post("/api/xamops/cost/refresh");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter().doFilter(request, response, chain);

        // No header, but the path is not gated: it passes straight through.
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void getOnGatedPrefix_isUntouched() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/autospotting/v2/config");
        request.setServletPath("/api/autospotting/v2/config");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter().doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void crossSiteFetch_is403OriginRejected() throws Exception {
        MockHttpServletRequest request = post("/api/autospotting/v2/accounts/1/plans");
        request.setContentType("application/json");
        request.addHeader(AutoSpottingIntentFilter.INTENT_HEADER, AutoSpottingIntentFilter.INTENT_VALUE);
        request.addHeader("Sec-Fetch-Site", "cross-site");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter().doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("CSRF_ORIGIN_REJECTED");
    }

    @Test
    void foreignOrigin_is403OriginRejected() throws Exception {
        MockHttpServletRequest request = post("/api/autospotting/v2/accounts/1/plans");
        request.setContentType("application/json");
        request.addHeader(AutoSpottingIntentFilter.INTENT_HEADER, AutoSpottingIntentFilter.INTENT_VALUE);
        request.addHeader("Origin", "https://evil.example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter().doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("CSRF_ORIGIN_REJECTED");
    }

    @Test
    void allowedOrigin_passes() throws Exception {
        MockHttpServletRequest request = post("/api/autospotting/v2/accounts/1/plans");
        request.setContentType("application/json");
        request.addHeader(AutoSpottingIntentFilter.INTENT_HEADER, AutoSpottingIntentFilter.INTENT_VALUE);
        request.addHeader("Origin", ORIGIN);
        request.addHeader("Sec-Fetch-Site", "same-origin");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter().doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void startupFails_onWildcardOrTunnelOrigin() {
        assertThatThrownBy(() -> new AutoSpottingIntentFilter(PREFIXES, List.of("https://*.xamops.com")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wildcard");
        assertThatThrownBy(() -> new AutoSpottingIntentFilter(PREFIXES, List.of("https://abc.ngrok.io")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tunnel");
    }
}
