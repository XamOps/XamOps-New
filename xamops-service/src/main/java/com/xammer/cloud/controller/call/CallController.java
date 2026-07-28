package com.xammer.cloud.controller.call;

import com.xammer.cloud.domain.call.CallParticipant;
import com.xammer.cloud.domain.call.CallSession;
import com.xammer.cloud.dto.call.CallSessionDto;
import com.xammer.cloud.dto.call.CallTokenResponse;
import com.xammer.cloud.dto.call.InitiateCallRequest;
import com.xammer.cloud.service.call.CallPrincipalFactory;
import com.xammer.cloud.service.call.CallService;
import com.xammer.cloud.service.call.CallUserPrincipal;
import com.xammer.cloud.service.call.CallWsTokenService;
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

    public CallController(CallService callService,
            CallWsTokenService wsTokenService,
            CallPrincipalFactory principalFactory) {
        this.callService = callService;
        this.wsTokenService = wsTokenService;
        this.principalFactory = principalFactory;
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
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error(e.getMessage()));
        } catch (IllegalStateException e) {
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
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error(e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error(e.getMessage()));
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
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error(e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(error(e.getMessage()));
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
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error(e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(error(e.getMessage()));
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
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error(e.getMessage()));
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
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error(e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error(e.getMessage()));
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
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error(e.getMessage()));
        }
    }

    /** Call history for a ticket, including missed attempts. */
    @GetMapping("/ticket/{ticketId}")
    public ResponseEntity<?> history(@PathVariable Long ticketId, Authentication authentication) {
        CallUserPrincipal me = principalFactory.fromAuthentication(authentication);
        if (me == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        List<CallSessionDto> history = callService.historyForTicket(ticketId).stream()
                .map(CallSessionDto::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(history);
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
}
