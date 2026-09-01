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

    private final CloudShellSocketHandler shellHandler;

    /**
     * Same list the CORS config uses, so the shell endpoint cannot drift wider than the
     * rest of the app.
     */
    @Value("${cors.allowed-origins}")
    private String allowedOrigins;

    public CloudShellWebSocketConfig(CloudShellSocketHandler shellHandler) {
        this.shellHandler = shellHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // MOVED to /terminal to avoid conflict with /ws (STOMP/SockJS) endpoint
        //
        // Origins were "*". The WebSocket handshake carries the session cookie and the
        // browser's same-origin policy does not apply to it, so a wildcard here meant any
        // website a logged-in user visited could open a shell in their cloud account
        // (cross-site WebSocket hijacking) — no XSS required. Pinning the origin is the
        // control that stops that; the ownership check in the handler bounds which account
        // a legitimately-connected user can reach.
        registry.addHandler(shellHandler, "/terminal")
                .setAllowedOriginPatterns(allowedOrigins.split(","));
    }
}
