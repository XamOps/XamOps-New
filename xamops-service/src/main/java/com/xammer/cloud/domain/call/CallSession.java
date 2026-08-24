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
     * How long agents are actively interrupted with a ringtone.
     *
     * Enforced by the server sweeper, never by the browser — a client that closes
     * mid-ring must not be able to strand the call in RINGING. Passing this deadline
     * no longer ends the call; it moves it into the quiet queue phase.
     */
    @Column(name = "ring_expires_at", nullable = false)
    private LocalDateTime ringExpiresAt;

    /**
     * How long the caller waits overall before the call is recorded as missed.
     *
     * Much longer than the ring window, because a support caller is willing to hold
     * for an agent to become free — and closing the call the instant nobody grabbed
     * it in 40 seconds made a busy team look like an absent one.
     */
    @Column(name = "queue_expires_at")
    private LocalDateTime queueExpiresAt;

    /**
     * True once the loud ring window has passed and the call is waiting quietly.
     *
     * Marks the phase rather than introducing a new state, so accept, decline, join
     * and cancel keep working unchanged. It also makes the escalation idempotent —
     * without it the sweeper would re-announce the same call on every pass, since
     * the ring deadline stays in the past forever.
     *
     * The column default is spelled out because Hibernate's {@code ddl-auto=update}
     * is what applies this on environments where Flyway is disabled, and it emits a
     * bare {@code add column queued boolean not null}. Postgres refuses that on a
     * table that already holds rows — there is no value to put in them — so without
     * the default the whole application fails to start.
     *
     * The definition carries only the type and default; the NOT NULL comes from
     * {@code nullable = false}. Putting it in both places makes Hibernate emit
     * "boolean not null default false not null", which is not portable.
     */
    @Column(name = "queued", nullable = false, columnDefinition = "boolean default false")
    private boolean queued = false;

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
