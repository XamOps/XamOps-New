package com.xammer.cloud.config;

import org.apache.hc.client5.http.cookie.CookieStore;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.cookie.BasicCookieStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;

/**
 * Spring Framework 7 dropped HttpClient 4 support from
 * {@link HttpComponentsClientHttpRequestFactory} and removed its
 * {@code setConnectTimeout}: the connect timeout is a property of the connection
 * manager in HttpClient 5, not of the request. Every client below is therefore built
 * from a {@link HttpClient5Support} pooling connection manager that carries both the
 * pool sizes and the connect timeout, with the read/connection-request timeouts still
 * set on the factory.
 */
@Configuration
public class RestTemplateConfig {

    /**
     * RestTemplate configured to handle HTTPS with CloudFront + ACM certificates
     */
    @Bean
    @Primary
    public RestTemplate restTemplate() throws KeyStoreException, NoSuchAlgorithmException, KeyManagementException {

        // Create cookie store to maintain sessions (required for CloudiDR API)
        CookieStore cookieStore = new BasicCookieStore();

        // 15 s connect — fail fast if server is unreachable
        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(HttpClient5Support.trustAllConnectionManager(100, 20, 15000))
                .setDefaultCookieStore(cookieStore)
                .build();

        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);
        // 5 minutes — purchase endpoint provisions real AWS capacity and can take 2-3 minutes
        factory.setReadTimeout(300000);

        return new RestTemplate(factory);
    }

    /**
     * Dedicated RestTemplate for Authentik API calls.
     *
     * IMPORTANT: No logging interceptor — the Authorization: Bearer header must
     * never appear in any log. Timeouts are shorter than the primary template
     * because Authentik is a local service (same network) and should be fast.
     */
    @Bean
    @Qualifier("authentikRestTemplate")
    public RestTemplate authentikRestTemplate() {
        // 5 s connect — fail fast if Authentik is unreachable
        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(HttpClient5Support.connectionManager(5000))
                .build();
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(15000);     // 15 s — covers slow list endpoints
        return new RestTemplate(factory);
    }

    /**
     * Short-timeout RestTemplate for Prometheus queries.
     * Prometheus should respond in milliseconds; 8 s is generous.
     * Keeping the timeout small prevents a single unreachable Prometheus endpoint
     * from blocking the entire dashboard aggregation job for minutes.
     */
    @Bean
    @Qualifier("prometheusRestTemplate")
    public RestTemplate prometheusRestTemplate() {
        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(HttpClient5Support.connectionManager(8000))
                .build();
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(8000);     // 8 seconds
        return new RestTemplate(factory);
    }

    /**
     * RestTemplate for direct LLM provider calls (OpenAI, Anthropic, Gemini).
     * 30s read timeout covers slow first-token latency without hanging forever.
     */
    @Bean
    @Qualifier("llmRestTemplate")
    public RestTemplate llmRestTemplate() throws KeyStoreException, NoSuchAlgorithmException, KeyManagementException {
        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(HttpClient5Support.trustAllConnectionManager(50, 10, 10000)) // 10s connect
                .build();
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(30000);    // 30s read
        return new RestTemplate(factory);
    }

    /**
     * RestTemplate for OpenObserve API calls.
     *
     * The Observability screens fan out hard — the Metrics tab alone issues ~18
     * concurrent query_range calls — so the pool must allow real parallelism
     * (HttpClient's built-in default is only 2 connections per route, which would
     * serialise the whole dashboard). Timeouts are mandatory: O2 is a remote
     * service and a bare RestTemplate waits forever, pinning a Tomcat thread and
     * leaving the browser spinner up indefinitely.
     */
    @Bean
    @Qualifier("openObserveRestTemplate")
    public RestTemplate openObserveRestTemplate() {
        // one route (the O2 host) — must cover the dashboard fan-out.
        // 5 s connect — O2 is same-network, fail fast if down
        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(HttpClient5Support.connectionManager(100, 50, 5000))
                .build();
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(20000);             // 20 s — covers wide-range PromQL / stream stats
        factory.setConnectionRequestTimeout(5000); // 5 s — don't queue forever on a saturated pool
        return new RestTemplate(factory);
    }

    /**
     * RestTemplate for external OAuth providers (Google, Microsoft).
     *
     * Token and userinfo exchanges happen inline during a browser login, so they must fail
     * fast — a hung provider call would otherwise occupy a request thread indefinitely and
     * leave the user staring at a spinner.
     */
    @Bean
    @Qualifier("oauthRestTemplate")
    public RestTemplate oauthRestTemplate() {
        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(HttpClient5Support.connectionManager(5000)) // 5s
                .build();
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(15000);    // 15s
        return new RestTemplate(factory);
    }

    /**
     * RestTemplate for calls to our own internal sidecar services (e.g. the Python
     * marketplace-procurement service). Bounded so an unresponsive sidecar can't pin
     * request threads.
     */
    @Bean
    @Qualifier("internalServiceRestTemplate")
    public RestTemplate internalServiceRestTemplate() {
        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(HttpClient5Support.connectionManager(5000)) // 5s — same network
                .build();
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(60000);     // 60s — some catalog operations are slow
        return new RestTemplate(factory);
    }

    /**
     * RestTemplate for Matters.ai DSPM calls.
     *
     * Its own bean rather than the primary one for two reasons. The primary template carries a
     * 300 s read timeout, sized for an endpoint that provisions real AWS capacity — every DSPM call
     * happens inline in a page load, so a stalled vendor would pin a Tomcat thread for five minutes
     * and the browser would give up long before (apiClient aborts at 45 s). And the primary template
     * shares a {@link BasicCookieStore} across all its callers, which an API-key-authenticated
     * client has no use for and should not be writing into.
     *
     * Measured latency against the live tenant is ~150 ms, so 20 s read is already generous and
     * leaves room for a large {@code page_size=100} page. Full TLS verification, unlike the
     * trust-all managers above: this is a public endpoint with a valid certificate and there is no
     * legacy behaviour to preserve.
     *
     * Pool sized for the filter rail, which issues the list and the faceted filter call together,
     * plus a little headroom for concurrent users on the same host.
     */
    @Bean
    @Qualifier("mattersRestTemplate")
    public RestTemplate mattersRestTemplate() {
        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(HttpClient5Support.connectionManager(20, 10, 5000)) // 5 s connect
                .build();
        HttpComponentsClientHttpRequestFactory factory =
                new HttpComponentsClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(20000);             // 20 s — ~150 ms observed, generous for a 100-row page
        factory.setConnectionRequestTimeout(5000); // don't queue forever on a saturated pool
        return new RestTemplate(factory);
    }

    /**
     * Short-timeout RestTemplate for LiteLLM sidecar calls.
     * LiteLLM is optional — if it's not running we must fail fast so the
     * calling thread (even async) doesn't block for 15+ seconds.
     */
    @Bean
    @Qualifier("litellmRestTemplate")
    public RestTemplate litellmRestTemplate() {
        // 2s connect — fail fast if LiteLLM not running
        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(HttpClient5Support.connectionManager(2000))
                .build();
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(5000);    // 5s read
        return new RestTemplate(factory);
    }
}
