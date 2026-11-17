package com.xammer.cloud.config.logging;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Design 5.11: the MDC mark covers exactly the {@code /api/dspm/**} requests and their error dispatches. */
class DspmLogScopeFilterTest {

    private static final String KEY = DspmQueryStringTurboFilter.MDC_SCOPE_KEY;

    private final DspmLogScopeFilter filter = new DspmLogScopeFilter();
    private final List<String> seen = new ArrayList<>();
    private final FilterChain recordingChain = (request, response) -> seen.add(MDC.get(KEY));

    @AfterEach
    void clearMdc() {
        MDC.remove(KEY);
    }

    private static MockHttpServletRequest request(String uri) {
        return new MockHttpServletRequest("GET", uri);
    }

    private static MockHttpServletRequest errorDispatch(String originalUri) {
        MockHttpServletRequest request = request("/error");
        request.setDispatcherType(DispatcherType.ERROR);
        if (originalUri != null) {
            request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, originalUri);
        }
        return request;
    }

    @Test
    @DisplayName("an /api/dspm/ request is marked while the chain runs, and the mark is gone afterwards")
    void marksXamLensRequests() throws Exception {
        filter.doFilter(request("/api/dspm/datastores"), new MockHttpServletResponse(), recordingChain);

        assertThat(seen).containsExactly("1");
        assertThat(MDC.get(KEY)).isNull();
    }

    @Test
    @DisplayName("the mark is removed when the chain throws")
    void clearsTheMarkOnFailure() {
        FilterChain failing = (request, response) -> {
            seen.add(MDC.get(KEY));
            throw new ServletException("boom");
        };

        assertThatThrownBy(() -> filter.doFilter(request("/api/dspm/datastores/kpis"),
                new MockHttpServletResponse(), failing)).isInstanceOf(ServletException.class);
        assertThat(seen).containsExactly("1");
        assertThat(MDC.get(KEY)).isNull();
    }

    @Test
    @DisplayName("other paths, a look-alike prefix included, are not marked")
    void leavesOtherRequestsAlone() throws Exception {
        filter.doFilter(request("/api/finops/x"), new MockHttpServletResponse(), recordingChain);
        filter.doFilter(request("/api/dspmx"), new MockHttpServletResponse(), recordingChain);

        assertThat(seen).containsExactly(null, null);
    }

    @Test
    @DisplayName("an error dispatch is marked by the URI of the request that failed")
    void marksErrorDispatchesOfXamLensRequests() throws Exception {
        filter.doFilter(errorDispatch("/api/dspm/datastores"), new MockHttpServletResponse(), recordingChain);

        assertThat(seen).containsExactly("1");
        assertThat(MDC.get(KEY)).isNull();
    }

    @Test
    @DisplayName("an error dispatch of another request, or one without the attribute, is not marked")
    void leavesOtherErrorDispatchesAlone() throws Exception {
        filter.doFilter(errorDispatch("/api/finops/x"), new MockHttpServletResponse(), recordingChain);
        filter.doFilter(errorDispatch(null), new MockHttpServletResponse(), recordingChain);

        assertThat(seen).containsExactly(null, null);
    }
}
