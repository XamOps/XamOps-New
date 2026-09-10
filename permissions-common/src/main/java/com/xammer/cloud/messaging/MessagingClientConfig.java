package com.xammer.cloud.messaging;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/**
 * Builds the single {@link RestClient} used to reach the SMS / WhatsApp provider.
 *
 * <p>Declared explicitly rather than reusing an injected {@code RestClient.Builder} because
 * this module deliberately does not depend on the web starter, and because the timeouts matter:
 * every other outbound HTTP call in the platform goes through a bare {@code new RestTemplate()}
 * with no connect or read timeout. Those calls run on {@code @Scheduled} threads that also send
 * email, so an unresponsive provider would stall an entire alert scan or report run rather than
 * failing one message.
 */
@Configuration
@EnableConfigurationProperties(MessagingProperties.class)
public class MessagingClientConfig {

    /**
     * Qualified name so it cannot be confused with, or accidentally satisfy, an application's
     * own {@code RestClient} bean.
     */
    @Bean("messagingRestClient")
    public RestClient messagingRestClient(MessagingProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getReadTimeout());

        // No baseUrl here: every call passes an absolute URI so the api_key query parameter
        // can be attached without it ever appearing in a logged path.
        return RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }
}
