package com.xammer.cloud.websocket;

import com.xammer.cloud.service.call.CallPresenceService;
import com.xammer.cloud.service.call.CallUserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;

/**
 * Keeps the call presence registry in step with socket lifecycle.
 *
 * Only sockets that authenticated with a call token carry a
 * {@link CallUserPrincipal}, so ordinary dashboard subscriptions never register
 * as call-reachable — which is what we want, since ringing a tab that cannot
 * render the incoming-call sheet just burns a ring.
 */
@Component
public class CallPresenceListener {

    private static final Logger log = LoggerFactory.getLogger(CallPresenceListener.class);

    private final CallPresenceService presenceService;

    public CallPresenceListener(CallPresenceService presenceService) {
        this.presenceService = presenceService;
    }

    @EventListener
    public void onConnected(SessionConnectedEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        CallUserPrincipal principal = asCallPrincipal(accessor.getUser());
        if (principal == null) {
            return;
        }
        presenceService.markOnline(principal.getUserId(), accessor.getSessionId());
        log.debug("User {} is reachable for calls (session {})",
                principal.getUserId(), accessor.getSessionId());
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        CallUserPrincipal principal = asCallPrincipal(event.getUser());
        if (principal == null) {
            return;
        }
        presenceService.markOffline(principal.getUserId(), event.getSessionId());
        log.debug("User {} dropped a call socket (session {})",
                principal.getUserId(), event.getSessionId());
    }

    private CallUserPrincipal asCallPrincipal(Principal principal) {
        return (principal instanceof CallUserPrincipal) ? (CallUserPrincipal) principal : null;
    }
}
