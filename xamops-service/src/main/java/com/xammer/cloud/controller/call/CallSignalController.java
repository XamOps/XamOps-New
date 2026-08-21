package com.xammer.cloud.controller.call;

import com.xammer.cloud.service.call.CallPresenceService;
import com.xammer.cloud.service.call.CallService;
import com.xammer.cloud.service.call.CallUserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;

import java.security.Principal;

/**
 * What clients send over the call socket.
 *
 * Call control runs over REST, since those operations need real status codes and
 * return join tokens. The socket carries rings outward, and these two liveness
 * signals inward:
 *
 * <ul>
 * <li>{@code /calls/ping} — "I could take a call", refreshing presence so a
 * long-idle tab stays ringable.</li>
 * <li>{@code /calls/heartbeat} — "I am still in this room", which is what lets
 * the server tell a live conversation from an abandoned one. A browser that dies
 * never sends a leave request, so without this a room stays occupied forever and
 * its ticket can never host another call.</li>
 * </ul>
 */
@Controller
public class CallSignalController {

    private static final Logger log = LoggerFactory.getLogger(CallSignalController.class);

    private final CallPresenceService presenceService;
    private final CallService callService;

    public CallSignalController(CallPresenceService presenceService, CallService callService) {
        this.presenceService = presenceService;
        this.callService = callService;
    }

    @MessageMapping("/calls/ping")
    public void ping(Principal principal, SimpMessageHeaderAccessor accessor) {
        if (principal instanceof CallUserPrincipal me) {
            presenceService.touch(me.getUserId(), accessor.getSessionId());
        }
    }

    /**
     * Liveness for one call. The payload is the call id as plain text, which keeps
     * the hottest message on this socket free of JSON parsing on both ends.
     *
     * Failures are swallowed deliberately: a heartbeat is advisory, and an exception
     * thrown out of a STOMP handler would be logged as an unhandled messaging error
     * on every tick of a call the user has already left.
     */
    @MessageMapping("/calls/heartbeat")
    public void heartbeat(Principal principal, @Payload String callIdRaw) {
        if (!(principal instanceof CallUserPrincipal me) || callIdRaw == null || callIdRaw.isBlank()) {
            return;
        }
        try {
            callService.heartbeat(me, Long.valueOf(callIdRaw.trim()));
        } catch (NumberFormatException e) {
            log.debug("Ignoring call heartbeat with unparseable id '{}'", callIdRaw);
        } catch (Exception e) {
            log.debug("Call heartbeat from user {} failed: {}", me.getUserId(), e.getMessage());
        }
    }
}
