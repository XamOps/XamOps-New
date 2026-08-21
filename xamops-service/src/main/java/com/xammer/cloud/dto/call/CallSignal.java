package com.xammer.cloud.dto.call;

import lombok.Data;

import java.time.Instant;

/**
 * One event on a call, pushed over STOMP.
 *
 * Every recipient of a call gets the same event stream; the {@code type} tells
 * their UI what to do. The distinction that matters is INCOMING (ring, show the
 * accept sheet) versus JOINABLE (stop ringing, but keep offering a way in) — that
 * is what lets a second admin drop into a call another admin already answered.
 */
@Data
public class CallSignal {

    public enum Type {
        /** Ring. Show the incoming-call sheet and start the ringtone. */
        INCOMING,
        /** Caller-side acknowledgement that the ring set was notified. */
        RINGING,
        /** Someone accepted. Recipients stop ringing; the answerer gets a token. */
        ANSWERED,
        /** Call is live and this recipient may still join it. */
        JOINABLE,
        /** A participant entered or left the room. */
        PARTICIPANT_CHANGED,
        /** Terminal: room closed. */
        ENDED,
        /** Terminal: nobody accepted before the ring deadline. */
        MISSED,
        /** Terminal: caller hung up before anyone accepted. */
        CANCELLED,
        /** Terminal: every recipient dismissed the call. */
        DECLINED,
        /** Nobody in the ring set had a socket open. */
        NO_ANSWER_AVAILABLE
    }

    private Type type;
    private Long callId;
    private String roomName;
    private Long ticketId;
    private String ticketSubject;
    /**
     * Ticket category and severity, carried so the person being rung can judge a
     * call before answering it. Everyone in the BillOps pool is rung either way, so
     * this is context rather than routing.
     */
    private String ticketCategory;
    private String ticketSeverity;
    private String direction;
    private String mediaKind;

    private Long initiatorUserId;
    private String initiatorName;

    private Long answeredByUserId;
    private String answeredByName;

    /** Seconds left before the ring expires; drives the caller-side countdown. */
    private Long ringExpiresInSeconds;

    private Integer activeParticipants;
    private String reason;
    private Instant at = Instant.now();

    public static CallSignal of(Type type, Long callId) {
        CallSignal s = new CallSignal();
        s.setType(type);
        s.setCallId(callId);
        return s;
    }
}
