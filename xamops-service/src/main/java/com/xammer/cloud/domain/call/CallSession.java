package com.xammer.cloud.domain.call;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import java.time.LocalDateTime;

/**
 * One call room attached to a support ticket.
 *
 * A call fans out to a set of people rather than a single callee. The first to
 * accept becomes the answerer and moves the session to ACTIVE; everyone else who
 * was rung stops ringing but keeps the option to join the same room while it
 * stays ACTIVE.
 */
@Entity
@Table(name = "call_session")
@Getter
@Setter
public class CallSession {

    public enum State {
        RINGING,
        ACTIVE,
        ENDED,
        /** Ring deadline passed with nobody accepting. */
        MISSED,
        /** Every rung participant actively dismissed it. */
        DECLINED,
        /** Initiator hung up before anyone accepted. */
        CANCELLED
    }

    public enum Direction {
        /** Customer rang the BillOps admin pool. */
        USER_TO_ADMIN,
        /** Admin rang the users who can see the ticket's account. */
        ADMIN_TO_USER
    }

    public enum MediaKind {
        AUDIO,
        VIDEO
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** LiveKit room name. Unique so a room is never silently reused across calls. */
    @Column(name = "room_name", nullable = false, unique = true, length = 128)
    private String roomName;

    @Column(name = "ticket_id", nullable = false)
    private Long ticketId;

    @Column(name = "client_id")
    private Long clientId;

    /**
     * Cloud provider account identifier copied from the ticket at call time (AWS
     * account number / GCP project id), retained so the ring set stays auditable
     * even if the ticket is later reassigned.
     */
    @Column(name = "account_id")
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Direction direction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private State state;

    @Enumerated(EnumType.STRING)
    @Column(name = "media_kind", nullable = false, length = 16)
    private MediaKind mediaKind = MediaKind.AUDIO;

    @Column(name = "initiator_user_id", nullable = false)
    private Long initiatorUserId;

    @Column(name = "initiator_username", nullable = false)
    private String initiatorUsername;

    @Column(name = "initiator_display_name")
    private String initiatorDisplayName;

    @Column(name = "answered_by_user_id")
    private Long answeredByUserId;

    @Column(name = "answered_by_username")
    private String answeredByUsername;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /**
     * Ring deadline. Enforced by the server sweeper, never by the browser — a
     * client that closes mid-ring must not be able to strand the call in RINGING.
     */
    @Column(name = "ring_expires_at", nullable = false)
    private LocalDateTime ringExpiresAt;

    @Column(name = "answered_at")
    private LocalDateTime answeredAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Column(name = "end_reason", length = 64)
    private String endReason;

    /** True while the room can still be joined by someone who was rung. */
    public boolean isLive() {
        return state == State.RINGING || state == State.ACTIVE;
    }
}
