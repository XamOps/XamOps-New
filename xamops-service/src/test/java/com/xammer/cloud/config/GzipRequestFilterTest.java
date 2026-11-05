package com.xammer.cloud.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PERF-8: the backend inflates gzip-encoded agent bodies before @RequestBody binding, and leaves
 * plain bodies untouched (backward compatible with pre-gzip agents).
 */
class GzipRequestFilterTest {

    private static final String JSON = "{\"schemaVersion\":\"1.0\",\"batches\":[{\"collector\":\"db-table-sizes\"}]}";

    private final GzipRequestFilter filter = new GzipRequestFilter();

    private static byte[] gzip(String s) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream zip = new GZIPOutputStream(out)) {
            zip.write(s.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    private String runAndCapture(MockHttpServletRequest request) throws Exception {
        AtomicReference<String> captured = new AtomicReference<>();
        FilterChain chain = (req, res) -> {
            byte[] body = ((HttpServletRequest) req).getInputStream().readAllBytes();
            captured.set(new String(body, StandardCharsets.UTF_8));
        };
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return captured.get();
    }

    @Test
    void inflatesGzipEncodedAgentBody() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/agent/ingest");
        request.setContentType("application/json");
        request.addHeader("Content-Encoding", "gzip");
        request.setContent(gzip(JSON));

        assertThat(runAndCapture(request)).isEqualTo(JSON);
    }

    @Test
    void passesPlainBodyThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/agent/ingest");
        request.setContentType("application/json");
        request.setContent(JSON.getBytes(StandardCharsets.UTF_8));

        assertThat(runAndCapture(request)).isEqualTo(JSON);
    }
}
