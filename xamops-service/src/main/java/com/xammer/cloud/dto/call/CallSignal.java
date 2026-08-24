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
        /**
         * The loud ring window has passed; the call is now waiting quietly in the
         * queue.
         *
         * Recipients stop ringing but keep the call in their waiting list, and the
         * caller is told they are still holding rather than being cut off. This is
         * what makes a support queue behave like one instead of a phone that gives up
         * after forty seconds.
         */
        QUEUED,
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

    /**
     * True on an INCOMING that reached somebody already on another call.
     *
     * The recipient is still a real ring target — they may well want to wrap up and
     * take it — but their UI must present it as a waiting call rather than seizing
     * the screen and playing a ringtone over a live conversation.
     *
     * Decided by the server because the browser cannot: "am I on a call?" used to
     * live in one tab's memory, so a second tab believed the user was free and rang
     * at full volume.
     */
    private boolean waiting;

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
