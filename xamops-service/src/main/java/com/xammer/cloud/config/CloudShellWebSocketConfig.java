package com.xammer.cloud.config;

import com.xammer.cloud.websocket.CloudShellSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class CloudShellWebSocketConfig implements WebSocketConfigurer {

    private final CloudShellSocketHandler shellHandler;

    public CloudShellWebSocketConfig(CloudShellSocketHandler shellHandler) {
        this.shellHandler = shellHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // MOVED to /terminal to avoid conflict with /ws (STOMP/SockJS) endpoint
        registry.addHandler(shellHandler, "/terminal")
                .setAllowedOriginPatterns("*");
    }
}