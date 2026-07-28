package com.xammer.cloud.websocket;

import com.xammer.cloud.service.call.CallUserPrincipal;
import com.xammer.cloud.service.call.CallWsTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * Attaches a caller identity to STOMP sessions that present a call token.
 *
 * Identity has to arrive in-band because the handshake cannot supply it: the
 * tickets UI is authenticated against billops, the socket terminates on
 * xamops-service, and {@code /xamops-ws/**} is permitAll. The client fetches a
 * short-lived token over authenticated REST and sends it as a CONNECT header.
 *
 * A missing or bad token is not an error — the connection proceeds without a
 * principal, which is exactly how the pre-existing dashboard and ticket-refresh
 * subscriptions already work. It only means user-targeted destinations
 * ({@code /user/queue/calls}) will not route, so that socket simply cannot take
 * calls.
 */
@Component
public class CallStompAuthInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(CallStompAuthInterceptor.class);
    public static final String TOKEN_HEADER = "X-Call-Token";

    private final CallWsTokenService tokenService;

    public CallStompAuthInterceptor(CallWsTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) {
            return message;
        }

        String token = accessor.getFirstNativeHeader(TOKEN_HEADER);
        if (token == null || token.isBlank()) {
            return message;
        }

        CallUserPrincipal principal = tokenService.verify(token);
        if (principal == null) {
            log.debug("Call socket presented an invalid token; connecting without identity");
            return message;
        }

        accessor.setUser(principal);
        log.debug("Call socket authenticated for user {} ({})",
                principal.getUserId(), principal.getUsername());
        return message;
    }
}
