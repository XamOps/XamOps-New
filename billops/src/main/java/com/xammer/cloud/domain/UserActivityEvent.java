package com.xammer.cloud.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * BillOps-side mapping of the shared {@code user_activity_event} table.
 *
 * <p><b>Must stay column-identical to
 * {@code xamops-service/.../domain/UserActivityEvent.java}.</b> Both services point at the
 * same PostgreSQL database and both run with {@code ddl-auto=update}, so a column declared
 * here but not there would be silently added to the live table by whichever service starts
 * first. BillOps also has no Flyway dependency — the migration that creates this table lives
 * in xamops-service — so this class must never be the thing that defines the schema.
 *
 * <p>Duplicated rather than extracted to a shared module because that is the established
 * pattern for tables both services touch: {@code Ticket}, {@code TicketReply},
 * {@code SupportMessage} and {@code User}/{@code AppUser} are all mapped twice already.
 *
 * <p>BillOps writes exactly one action here — {@code IMPERSONATION_STARTED} from
 * {@code ImpersonationTokenController} — and reads nothing. The activity window itself is
 * served by xamops-service.
 */
@Entity
@Table(name = "user_activity_event", indexes = {
        @Index(name = "idx_uae_user_ts", columnList = "user_id, created_at DESC"),
        @Index(name = "idx_uae_client_ts", columnList = "client_id, created_at DESC")
})
@Data
@NoArgsConstructor
public class UserActivityEvent {

    public static final String ACTION_IMPERSONATION_STARTED = "IMPERSONATION_STARTED";
    public static final String OUTCOME_SUCCESS = "SUCCESS";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The account being entered, so the event appears in that user's own activity window. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "client_id")
    private Long clientId;

    @Column(name = "action", nullable = false, length = 100)
    private String action;

    @Column(name = "auth_provider", length = 50)
    private String authProvider;

    /** The admin who issued the token. */
    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "session_id", length = 128)
    private String sessionId;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "outcome", nullable = false, length = 20,
            columnDefinition = "varchar(20) default 'SUCCESS'")
    private String outcome = OUTCOME_SUCCESS;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    @Column(name = "metadata", columnDefinition = "TEXT")
    private String metadata;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
