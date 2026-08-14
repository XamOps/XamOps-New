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
     * Dedicated RestTemplate for OpenObserve (O2) calls.
     *
     * Timeouts are mandatory here: every Observability page load fans out into
     * ~19 parallel /metrics/query requests plus logs search/histogram polling.
     * A plain `new RestTemplate()` has no connect or read timeout, so a slow or
     * unreachable O2 pins Tomcat worker threads — and, on the provisioning path,
     * JDBC connections — indefinitely, which surfaces as
     * "Unable to acquire JDBC Connection" on every other endpoint.
     *
     * maxConnPerRoute is capped below the Tomcat thread count so O2 backpressure
     * shows up as a fast queue wait rather than an unbounded socket pile-up.
     */
    @Bean
    @Qualifier("openObserveRestTemplate")
    public RestTemplate openObserveRestTemplate() {
        CloseableHttpClient httpClient = HttpClients.custom()
                .setMaxConnTotal(60)
                .setMaxConnPerRoute(30)
                .build();
        HttpComponentsClientHttpRequestFactory factory =
                new HttpComponentsClientHttpRequestFactory(httpClient);
        factory.setConnectTimeout(5000);           // 5s — O2 is in-VPC, should connect instantly
        factory.setReadTimeout(20000);             // 20s — bounds the worst-case query
        factory.setConnectionRequestTimeout(5000); // 5s — don't queue forever for a pooled socket
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
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory();
        factory.setConnectTimeout(5000);  // 5s
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
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory();
        factory.setConnectTimeout(5000);   // 5s — same network
        factory.setReadTimeout(60000);     // 60s — some catalog operations are slow
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
