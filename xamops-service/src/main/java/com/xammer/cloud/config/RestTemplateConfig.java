package com.xammer.cloud.config;

import org.apache.http.client.CookieStore;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.ssl.SSLContextBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import javax.net.ssl.SSLContext;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;

@Configuration
public class RestTemplateConfig {

    /**
     * RestTemplate configured to handle HTTPS with CloudFront + ACM certificates
     */
    @Bean
    @Primary
    public RestTemplate restTemplate() throws KeyStoreException, NoSuchAlgorithmException, KeyManagementException {

        // Build SSL context that trusts all certificates (for CloudFront/ACM)
        SSLContext sslContext = SSLContextBuilder
                .create()
                .loadTrustMaterial((chain, authType) -> true) // Trust all certificates
                .build();

        // Create SSL socket factory with hostname verification disabled
        SSLConnectionSocketFactory socketFactory = new SSLConnectionSocketFactory(
                sslContext,
                NoopHostnameVerifier.INSTANCE // Skip hostname verification for CloudFront
        );

        // Create cookie store to maintain sessions (required for CloudiDR API)
        CookieStore cookieStore = new BasicCookieStore();

        // Build HTTP client with SSL support and cookie handling
        CloseableHttpClient httpClient = HttpClients.custom()
                .setSSLSocketFactory(socketFactory)
                .setDefaultCookieStore(cookieStore)
                .setMaxConnTotal(100)
                .setMaxConnPerRoute(20)
                .build();

        // Create request factory
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);
        factory.setConnectTimeout(15000); // 15 seconds — fail fast if server is unreachable
        factory.setReadTimeout(300000);  // 5 minutes — purchase endpoint provisions real AWS capacity and can take 2-3 minutes

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
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory();
        factory.setConnectTimeout(5000);   // 5 s — fail fast if Authentik is unreachable
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
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory();
        factory.setConnectTimeout(8000);  // 8 seconds
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
        SSLContext sslContext = SSLContextBuilder
                .create()
                .loadTrustMaterial((chain, authType) -> true)
                .build();
        SSLConnectionSocketFactory socketFactory = new SSLConnectionSocketFactory(
                sslContext, NoopHostnameVerifier.INSTANCE);
        CloseableHttpClient httpClient = HttpClients.custom()
                .setSSLSocketFactory(socketFactory)
                .setMaxConnTotal(50)
                .setMaxConnPerRoute(10)
                .build();
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);
        factory.setConnectTimeout(10000); // 10s connect
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
        CloseableHttpClient httpClient = HttpClients.custom()
                .setMaxConnTotal(100)
                .setMaxConnPerRoute(50)   // one route (the O2 host) — must cover the dashboard fan-out
                .build();
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);
        factory.setConnectTimeout(5000);           // 5 s — O2 is same-network, fail fast if down
        factory.setReadTimeout(20000);             // 20 s — covers wide-range PromQL / stream stats
        factory.setConnectionRequestTimeout(5000); // 5 s — don't queue forever on a saturated pool
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
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory();
        factory.setConnectTimeout(2000); // 2s — fail fast if LiteLLM not running
        factory.setReadTimeout(5000);    // 5s read
        return new RestTemplate(factory);
    }
}
