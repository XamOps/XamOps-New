package com.xammer.cloud.config;

import com.xammer.cloud.websocket.CallStompAuthInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Value("${cors.allowed-origins}")
    private String allowedOrigins;

    private final CallStompAuthInterceptor callStompAuthInterceptor;

    public WebSocketConfig(CallStompAuthInterceptor callStompAuthInterceptor) {
        this.callStompAuthInterceptor = callStompAuthInterceptor;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/xamops-ws")
                .setAllowedOriginPatterns(allowedOrigins.split(",")) // Split by comma to support multiple origins
                .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // /topic stays broadcast (dashboard inventory, ticket refresh pings).
        // /queue is required for user-targeted delivery: call signals go to one
        // person via convertAndSendToUser, which resolves to /user/{id}/queue/calls.
        //
        // Security findings use the same mechanism, at /user/{id}/queue/security-findings.
        // They deliberately do NOT use /topic: every topic here is broadcast with no
        // tenant filter, and /topic/dashboard/{awsAccountId}/inventory is guessable from
        // an account id, so publishing findings that way would expose one customer's
        // live vulnerabilities to another. Anything tenant-sensitive belongs on /queue.
        //
        // NOTE: this is the in-memory simple broker, so a user-targeted message only
        // reaches a recipient connected to the same instance. Running more than one
        // replica needs either sticky sessions or a shared relay — otherwise calls
        // between users on different instances fail silently.
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(callStompAuthInterceptor);
    }
}
