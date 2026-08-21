package com.xammer.cloud.controller.call;

import com.xammer.cloud.domain.call.CallParticipant;
import com.xammer.cloud.domain.call.CallSession;
import com.xammer.cloud.dto.call.CallSessionDto;
import com.xammer.cloud.dto.call.CallTokenResponse;
import com.xammer.cloud.dto.call.InitiateCallRequest;
import com.xammer.cloud.dto.call.InvitableUserDto;
import com.xammer.cloud.dto.call.InviteCallRequest;
import com.xammer.cloud.service.call.CallPrincipalFactory;
import com.xammer.cloud.service.call.CallService;
import com.xammer.cloud.service.call.CallUserPrincipal;
import com.xammer.cloud.service.call.CallWsTokenService;
import com.xammer.cloud.service.call.LiveKitTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Call control for support tickets.
 *
 * Media negotiation is LiveKit's job, so nothing here exchanges SDP or ICE. This
 * API only decides who is allowed in a room and when, and hands out short-lived
 * join tokens; the ring itself is pushed over STOMP.
 */
@RestController
@RequestMapping("/api/xamops/calls")
public class CallController {

    private static final Logger log = LoggerFactory.getLogger(CallController.class);

    private final CallService callService;
    private final CallWsTokenService wsTokenService;
    private final CallPrincipalFactory principalFactory;
    private final LiveKitTokenService liveKitTokenService;

    public CallController(CallService callService,
            CallWsTokenService wsTokenService,
            CallPrincipalFactory principalFactory,
            LiveKitTokenService liveKitTokenService) {
        this.callService = callService;
        this.wsTokenService = wsTokenService;
        this.principalFactory = principalFactory;
        this.liveKitTokenService = liveKitTokenService;
    }

    /**
     * Token the browser presents when opening its call socket. Fetched over an
     * authenticated request precisely because the socket handshake itself cannot
     * establish identity across the billops/xamops split.
     */
    @GetMapping("/ws-token")
    public ResponseEntity<?> wsToken(Authentication authentication) {
        CallUserPrincipal me = principalFactory.fromAuthentication(authentication);
        if (me == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String token = wsTokenService.issue(me.getUserId(), me.getUsername(),
                me.getDisplayName(), me.getAuthorities(), me.getClientId());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", token);
        body.put("ttlSeconds", wsTokenService.getTtlSeconds());
        body.put("userId", me.getUserId());
        // Lets the UI disable the call buttons on environments with no LiveKit
        // rather than offering a control that always fails on click. Rides along
        // with the token the client already fetches, so it costs no extra request.
        body.put("callingEnabled", liveKitTokenService.isConfigured());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/initiate")
    public ResponseEntity<?> initiate(@RequestBody InitiateCallRequest request, Authentication authentication) {
        CallUserPrincipal me = principalFactory.fromAuthentication(authentication);
        if (me == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (request.getTicketId() == null) {
            return ResponseEntity.badRequest().body(error("ticketId is required"));
        }
        try {
            CallSession session = callService.initiate(me, request.getTicketId(), request.getMediaKind());
            return ResponseEntity.ok(CallSessionDto.from(session));
        } catch (CallService.CallAccessDeniedException e) {
            return denied(e, "You do not have access to calls on this ticket");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error("Ticket not found"));
        } catch (IllegalStateException e) {
            // Safe to pass through: this is the "LiveKit is not configured" case, which
            // the caller genuinely needs to read.
            return ResponseEntity.status(HttpStatus.CONFLICT).body(error(e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to initiate call on ticket {}", request.getTicketId(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error("Could not start the call"));
        }
    }

    /**
     * Accepts a ringing call. Returns 409 when someone else got there first — the
     * client's move then is to call {@code /join}, not to retry.
     */
    @PostMapping("/{callId}/accept")
    public ResponseEntity<?> accept(@PathVariable Long callId, Authentication authentication) {
        CallUserPrincipal me = principalFactory.fromAuthentication(authentication);
        if (me == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            boolean won = callService.accept(me, callId);
            if (!won) {
                Map<String, Object> body = error("Call was already answered");
                body.put("joinable", true);
                return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
            }
            return ResponseEntity.ok(callService.issueToken(me, callId));
        } catch (CallService.CallAccessDeniedException e) {
            return denied(e, "You were not invited to this call");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error("Call not found"));
        } catch (IllegalStateException e) {
            // The ring is gone — expired or cancelled between the sheet appearing and
            // the button being pressed. Not joinable, so the client must stand down.
            return ResponseEntity.status(HttpStatus.GONE)
                    .body(error("That call has already ended"));
        }
    }

    /** Joins a call already in progress that this user was rung for. */
    @PostMapping("/{callId}/join")
    public ResponseEntity<?> join(@PathVariable Long callId, Authentication authentication) {
        CallUserPrincipal me = principalFactory.fromAuthentication(authentication);
        if (me == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            callService.join(me, callId);
            CallTokenResponse token = callService.issueToken(me, callId);
            return ResponseEntity.ok(token);
        } catch (CallService.CallAccessDeniedException e) {
            return denied(e, "You were not invited to this call");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error("Call not found"));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(error("That call is no longer active"));
        }
    }

    /** Re-issues a join token, for a page reload mid-call or an expired token. */
    @PostMapping("/{callId}/token")
    public ResponseEntity<?> token(@PathVariable Long callId, Authentication authentication) {
        CallUserPrincipal me = principalFactory.fromAuthentication(authentication);
        if (me == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            return ResponseEntity.ok(callService.issueToken(me, callId));
        } catch (CallService.CallAccessDeniedException e) {
            return denied(e, "You are not in this call");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error("Call not found"));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(error("That call has already ended"));
        }
    }

    @PostMapping("/{callId}/decline")
    public ResponseEntity<?> decline(@PathVariable Long callId, Authentication authentication) {
        CallUserPrincipal me = principalFactory.fromAuthentication(authentication);
        if (me == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            callService.decline(me, callId);
            return ResponseEntity.noContent().build();
        } catch (CallService.CallAccessDeniedException e) {
            return denied(e, "You were not invited to this call");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error("Call not found"));
        }
    }

    @PostMapping("/{callId}/cancel")
    public ResponseEntity<?> cancel(@PathVariable Long callId, Authentication authentication) {
        CallUserPrincipal me = principalFactory.fromAuthentication(authentication);
        if (me == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            callService.cancel(me, callId);
            return ResponseEntity.noContent().build();
        } catch (CallService.CallAccessDeniedException e) {
            return denied(e, "Only the caller can cancel this call");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error("Call not found"));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(error("Only the caller can cancel this call"));
        }
    }

    @PostMapping("/{callId}/leave")
    public ResponseEntity<?> leave(@PathVariable Long callId, Authentication authentication) {
        CallUserPrincipal me = principalFactory.fromAuthentication(authentication);
        if (me == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            callService.leave(me, callId);
            return ResponseEntity.noContent().build();
        } catch (CallService.CallAccessDeniedException e) {
            // Leaving a call you are not in is already the desired end state, so this is
            // reported as success. Anything else would surface an error on a page the
            // user is simply navigating away from.
            log.debug("Ignoring leave from non-participant: {}", e.getMessage());
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error("Call not found"));
        }
    }

    /** Users the caller may pull into this call (scoped by role, server-side). */
    @GetMapping("/{callId}/invitable-users")
    public ResponseEntity<?> invitableUsers(@PathVariable Long callId, Authentication authentication) {
        CallUserPrincipal me = principalFactory.fromAuthentication(authentication);
        if (me == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            List<InvitableUserDto> users = callService.invitableUsers(me, callId).stream()
                    .map(InvitableUserDto::from)
                    .collect(Collectors.toList());
            return ResponseEntity.ok(users);
        } catch (CallService.CallAccessDeniedException e) {
            return denied(e, "You are not part of this call");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error("Call not found"));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(error("That call has already ended"));
        }
    }

    /** Pulls chosen users into a live call — rings the online ones and emails all. */
    @PostMapping("/{callId}/invite")
    public ResponseEntity<?> invite(@PathVariable Long callId,
            @RequestBody InviteCallRequest request, Authentication authentication) {
        CallUserPrincipal me = principalFactory.fromAuthentication(authentication);
        if (me == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            callService.invite(me, callId, request.getUserIds());
            return ResponseEntity.noContent().build();
        } catch (CallService.CallAccessDeniedException e) {
            return denied(e, "You cannot add those people to this call");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error("Call not found"));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(error("That call has already ended"));
        }
    }

    /** Call history for a ticket, including missed attempts. */
    @GetMapping("/ticket/{ticketId}")
    public ResponseEntity<?> history(@PathVariable Long ticketId, Authentication authentication) {
        CallUserPrincipal me = principalFactory.fromAuthentication(authentication);
        if (me == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            List<CallSessionDto> history = callService.historyForTicket(me, ticketId).stream()
                    .map(CallSessionDto::from)
                    .collect(Collectors.toList());
            return ResponseEntity.ok(history);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error("Ticket not found"));
        } catch (CallService.CallAccessDeniedException e) {
            // An empty list rather than a 403: the call strip is one panel on a page the
            // caller may legitimately be allowed to see in other respects, and a hard
            // error there would break the whole view over a supplementary section.
            log.debug("Denied call history for ticket {}: {}", ticketId, e.getMessage());
            return ResponseEntity.ok(List.of());
        }
    }

    /**
     * The call this user is currently in, if any.
     *
     * Lets the UI put someone back into their call after a page reload. Without it
     * the browser's copy of "I am on a call" was the only one, so a refresh left the
     * user still occupying the room server-side with no way to see or hear it.
     */
    @GetMapping("/active")
    public ResponseEntity<?> active(Authentication authentication) {
        CallUserPrincipal me = principalFactory.fromAuthentication(authentication);
        if (me == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return callService.activeCallFor(me.getUserId())
                .map(session -> ResponseEntity.ok((Object) CallSessionDto.from(session)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * Liveness ping from a client that is in a room.
     *
     * Also exposed over REST, not only STOMP, so that a browser whose signalling
     * socket is mid-reconnect does not look abandoned and get its call swept.
     *
     * @return 204 while the heartbeat is accepted, 409 once the server no longer
     *         considers this user present — the client's cue to stop and reconcile
     */
    @PostMapping("/{callId}/heartbeat")
    public ResponseEntity<?> heartbeat(@PathVariable Long callId, Authentication authentication) {
        CallUserPrincipal me = principalFactory.fromAuthentication(authentication);
        if (me == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        boolean stillIn = callService.heartbeat(me, callId);
        return stillIn
                ? ResponseEntity.noContent().build()
                : ResponseEntity.status(HttpStatus.CONFLICT).body(error("You are no longer in this call"));
    }

    /** This user's missed calls, for the badge on the tickets page. */
    @GetMapping("/missed")
    public ResponseEntity<?> missed(Authentication authentication) {
        CallUserPrincipal me = principalFactory.fromAuthentication(authentication);
        if (me == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        List<Map<String, Object>> missed = callService.missedFor(me.getUserId()).stream()
                .map(this::toMissedEntry)
                .collect(Collectors.toList());
        return ResponseEntity.ok(missed);
    }

    private Map<String, Object> toMissedEntry(CallParticipant p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("callId", p.getCallSessionId());
        m.put("participantId", p.getId());
        callService.find(p.getCallSessionId()).ifPresent(s -> {
            m.put("ticketId", s.getTicketId());
            m.put("initiatorName", s.getInitiatorDisplayName() != null
                    ? s.getInitiatorDisplayName()
                    : s.getInitiatorUsername());
            m.put("at", s.getCreatedAt());
            m.put("direction", s.getDirection() != null ? s.getDirection().name() : null);
        });
        return m;
    }

    private Map<String, Object> error(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        return body;
    }

    /**
     * 403 with a message a person can read.
     *
     * The internal detail is logged, never returned. The frontend renders the
     * {@code message} field of an error body directly into a toast, so the previous
     * behaviour of passing authorization failures through verbatim put strings like
     * "User 42 was not invited to call 137" in front of customers.
     */
    private ResponseEntity<?> denied(CallService.CallAccessDeniedException e, String userFacing) {
        log.debug("Call access denied: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error(userFacing));
    }
}
