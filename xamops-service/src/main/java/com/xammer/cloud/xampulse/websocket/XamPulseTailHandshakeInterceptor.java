package com.xammer.cloud.xampulse.websocket;

import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.xampulse.service.stream.TailTicketData;
import com.xammer.cloud.xampulse.service.stream.TailTicketService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.security.Principal;
import java.util.Map;
import java.util.Optional;

/**
 * Consumes the single-use ticket during the WebSocket handshake. No ticket, an expired or reused ticket, or one
 * presented by a different logged-in user is refused with 401 before the connection upgrades.
 */
@Component("xpTailHandshakeInterceptor")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class XamPulseTailHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_TICKET = "xp.ticket";

    private final TailTicketService tickets;

    public XamPulseTailHandshakeInterceptor(TailTicketService tickets) {
        this.tickets = tickets;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        String ticket = null;
        if (request instanceof ServletServerHttpRequest s) {
            ticket = s.getServletRequest().getParameter("ticket");
        }
        Long presentedUser = presentedUserId(request.getPrincipal());
        Optional<TailTicketData> data = tickets.consume(ticket, presentedUser);
        if (data.isEmpty()) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put(ATTR_TICKET, data.get());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Exception exception) {
        // nothing to do
    }

    static Long presentedUserId(Principal p) {
        if (p instanceof Authentication a && a.getPrincipal() instanceof ClientUserDetails u) {
            return u.getId();
        }
        if (p instanceof UsernamePasswordAuthenticationToken t && t.getPrincipal() instanceof ClientUserDetails u) {
            return u.getId();
        }
        return null;
    }
}
