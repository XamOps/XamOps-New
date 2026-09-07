package com.xammer.cloud.config;

import com.xammer.cloud.websocket.CloudShellSocketHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class CloudShellWebSocketConfig implements WebSocketConfigurer {

    /**
     * Same property the STOMP endpoint in {@link WebSocketConfig} uses, so both WebSocket
     * endpoints share one origin allowlist per environment.
     */
    @Value("${cors.allowed-origins}")
    private String allowedOrigins;

    private final CloudShellSocketHandler shellHandler;

    public CloudShellWebSocketConfig(CloudShellSocketHandler shellHandler) {
        this.shellHandler = shellHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // MOVED to /terminal to avoid conflict with /ws (STOMP/SockJS) endpoint
        //
        // The origin allowlist matters more here than on a normal endpoint: WebSocket
        // handshakes are not covered by the same-origin policy or by CORS, and this
        // endpoint authenticates from the session cookie. With the previous "*" pattern
        // any website a logged-in user visited could open a shell into their cloud
        // account using their ambient session.
        registry.addHandler(shellHandler, "/terminal")
                .setAllowedOriginPatterns(allowedOrigins.split(","));
    }
}
