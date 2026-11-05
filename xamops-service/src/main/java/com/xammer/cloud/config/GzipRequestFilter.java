package com.xammer.cloud.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

/**
 * PERF-8: transparently inflate gzip-compressed agent ingest bodies.
 *
 * <p>The Go agent now gzips the (often 400+ KB) ingest JSON and sends {@code Content-Encoding: gzip}.
 * Servlet containers do not inflate request bodies, so without this filter the
 * {@code @RequestBody AgentIngestPayload} binding in {@code AgentController.ingestData} would try to
 * parse the compressed bytes as JSON. When the header is present this wraps the request so
 * {@code getInputStream()} yields the inflated bytes; requests without the header pass through
 * untouched, so agents built before gzip keep working (backward compatible).
 *
 * <p>Scoped to {@code /api/v1/agent/**} — the only gzip producer — so no other endpoint pays the
 * header check. The wrapper is installed before {@code @RequestBody} binding regardless of filter
 * ordering, because the body is only read downstream in the controller.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class GzipRequestFilter extends OncePerRequestFilter {

    private static final String AGENT_PATH_PREFIX = "/api/v1/agent/";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        chain.doFilter(new GzipInflatingRequestWrapper(request), response);
    }

    /**
     * Act only on agent requests that actually declare gzip. Everything else — other paths and
     * uncompressed agent requests — skips the wrapper entirely.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String encoding = request.getHeader("Content-Encoding");
        boolean isGzip = encoding != null && encoding.toLowerCase().contains("gzip");
        return !isGzip || !request.getRequestURI().startsWith(AGENT_PATH_PREFIX);
    }

    /** Wraps a gzip-encoded request so the body is inflated on read. */
    private static final class GzipInflatingRequestWrapper extends HttpServletRequestWrapper {
        private final ServletInputStream inflated;
        private BufferedReader reader;

        GzipInflatingRequestWrapper(HttpServletRequest request) throws IOException {
            super(request);
            this.inflated = new InflatingServletInputStream(new GZIPInputStream(request.getInputStream()));
        }

        @Override
        public ServletInputStream getInputStream() {
            return inflated;
        }

        @Override
        public BufferedReader getReader() {
            if (reader == null) {
                reader = new BufferedReader(new InputStreamReader(inflated, StandardCharsets.UTF_8));
            }
            return reader;
        }

        /** The inflated length is unknown up front; let the reader consume to EOF. */
        @Override
        public int getContentLength() {
            return -1;
        }

        @Override
        public long getContentLengthLong() {
            return -1L;
        }
    }

    /** A {@link ServletInputStream} backed by the inflating stream. Sync reads only (ingest is sync). */
    private static final class InflatingServletInputStream extends ServletInputStream {
        private final InputStream delegate;
        private boolean finished;

        InflatingServletInputStream(InputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b == -1) {
                finished = true;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = delegate.read(b, off, len);
            if (n == -1) {
                finished = true;
            }
            return n;
        }

        @Override
        public boolean isFinished() {
            return finished;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            throw new UnsupportedOperationException("async reads not supported for inflated agent bodies");
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
