package com.xammer.cloud.xampulse.config;

import com.xammer.cloud.xampulse.websocket.XamPulseTailHandshakeInterceptor;
import com.xammer.cloud.xampulse.websocket.XamPulseTailWebSocketHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Registers the raw live-tail socket. {@code @EnableWebSocket} is already declared by the CloudShell config, so this
 * class only contributes a second {@link WebSocketConfigurer}; it does not touch the STOMP broker.
 */
@Configuration("xpWebSocketConfig")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class XamPulseWebSocketConfig implements WebSocketConfigurer {

    private final XamPulseTailWebSocketHandler handler;
    private final XamPulseTailHandshakeInterceptor interceptor;

    @Value("${cors.allowed-origins}")
    private String allowedOrigins;

    public XamPulseWebSocketConfig(XamPulseTailWebSocketHandler handler, XamPulseTailHandshakeInterceptor interceptor) {
        this.handler = handler;
        this.interceptor = interceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Same origin list as the rest of the app: the handshake carries the session cookie, so a wildcard here
        // would allow cross-site WebSocket hijacking.
        registry.addHandler(handler, "/xampulse-ws/tail")
                .addInterceptors(interceptor)
                .setAllowedOriginPatterns(allowedOrigins.split(","));
    }
}
