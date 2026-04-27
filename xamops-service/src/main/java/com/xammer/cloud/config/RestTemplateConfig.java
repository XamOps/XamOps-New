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
import org.springframework.http.client.BufferingClientHttpRequestFactory;
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

        // Wrap with buffering for request/response logging
        BufferingClientHttpRequestFactory bufferingFactory = new BufferingClientHttpRequestFactory(factory);

        RestTemplate restTemplate = new RestTemplate(bufferingFactory);

        // Add interceptor for debugging
        restTemplate.getInterceptors().add((request, body, execution) -> {
            System.out.println("═══════════════════════════════════");
            System.out.println("RestTemplate OUTGOING REQUEST");
            System.out.println("═══════════════════════════════════");
            System.out.println("URI: " + request.getURI());
            System.out.println("Method: " + request.getMethod());
            System.out.println("Headers:");
            request.getHeaders().forEach((name, values) -> {
                if (name.equalsIgnoreCase("X-Api-Key")) {
                    System.out.println("  " + name + ": " +
                            values.get(0).substring(0, Math.min(10, values.get(0).length())) +
                            "... (length=" + values.get(0).length() + ")");
                } else {
                    System.out.println("  " + name + ": " + values);
                }
            });

            var response = execution.execute(request, body);

            System.out.println("═══════════════════════════════════");
            System.out.println("RestTemplate RESPONSE");
            System.out.println("═══════════════════════════════════");
            System.out.println("Status: " + response.getStatusCode());
            System.out.println("Status Code: " + response.getRawStatusCode());
            System.out.println("═══════════════════════════════════");

            return response;
        });

        return restTemplate;
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
}
