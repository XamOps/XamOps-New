package com.xammer.cloud.config;

import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.ssl.SSLContextBuilder;
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

        // Build HTTP client with SSL support
        CloseableHttpClient httpClient = HttpClients.custom()
                .setSSLSocketFactory(socketFactory)
                .setMaxConnTotal(100)
                .setMaxConnPerRoute(20)
                .build();

        // Create request factory
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);
        factory.setConnectTimeout(30000); // 30 seconds
        factory.setReadTimeout(180000); // 3 minutes

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
}
