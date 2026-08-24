package com.xammer.cloud.controller.call;

import com.xammer.cloud.service.call.CallPresenceService;
import com.xammer.cloud.service.call.CallUserPrincipal;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;

import java.security.Principal;

/**
 * The only thing clients send over the call socket.
 *
 * Call control runs over REST, since those operations need real status codes and
 * return join tokens. The socket exists to push rings outward — and to carry this
 * heartbeat, which refreshes the presence TTL so a long-idle tab stays reachable
 * without the registry having to trust that disconnect events always arrive.
 *
 * Note that in-call liveness is a separate signal and deliberately does <em>not</em>
 * live here. It goes over REST ({@code POST /calls/{id}/heartbeat}) because the
 * client needs the reply: a 409 is how a browser discovers the call it thinks it is
 * on has already closed. A fire-and-forget STOMP frame cannot tell it that, so a
 * client that missed the ENDED signal would sit on a dead call indefinitely.
 */
@Controller
public class CallSignalController {

    private final CallPresenceService presenceService;

    public CallSignalController(CallPresenceService presenceService) {
        this.presenceService = presenceService;
    }

    @MessageMapping("/calls/ping")
    public void ping(Principal principal, SimpMessageHeaderAccessor accessor) {
        if (principal instanceof CallUserPrincipal me) {
            presenceService.touch(me.getUserId(), accessor.getSessionId());
        }
    }
}
