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
 * One discrete authentication-related event for a user.
 *
 * Supplements {@code app_user.last_login_at}, which is a single scalar overwritten on
 * every login and therefore cannot express history. Rows here are append-only; nothing
 * in the application updates or deletes them.
 *
 * <p>Indexes are declared on the annotation as well as in
 * {@code V20261015_1__user_activity_tracking.sql} on purpose. Local runs have
 * {@code spring.flyway.enabled=false} with {@code ddl-auto=update}, so anything living
 * only in the migration file does not exist on a developer machine — the table would be
 * created by Hibernate without its indexes and local query plans would diverge from
 * production.
 *
 * @see UserPageView for the interval-shaped counterpart covering page visits
 */
@Entity
@Table(name = "user_activity_event", indexes = {
        @Index(name = "idx_uae_user_ts", columnList = "user_id, created_at DESC"),
        @Index(name = "idx_uae_client_ts", columnList = "client_id, created_at DESC")
})
@Data
@NoArgsConstructor
public class UserActivityEvent {

    /** Successful sign-in by any mechanism. */
    public static final String ACTION_LOGIN = "LOGIN";
    /** Rejected sign-in attempt. Carries {@link #failureReason}. */
    public static final String ACTION_LOGIN_FAILED = "LOGIN_FAILED";
    /** Explicit sign-out. Absent when a user simply closes the tab. */
    public static final String ACTION_LOGOUT = "LOGOUT";
    /** A BillOps admin minted a "Login as" token for this user. */
    public static final String ACTION_IMPERSONATION_STARTED = "IMPERSONATION_STARTED";

    public static final String PROVIDER_LOCAL = "LOCAL";
    public static final String PROVIDER_OTP = "OTP";
    public static final String PROVIDER_GOOGLE = "GOOGLE";
    public static final String PROVIDER_MICROSOFT = "MICROSOFT";

    public static final String OUTCOME_SUCCESS = "SUCCESS";
    public static final String OUTCOME_FAILURE = "FAILURE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * XamOps {@code app_user.id}. Not a {@code @ManyToOne} — see the table comment in the
     * migration: an audit row must outlive the user it describes.
     */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "client_id")
    private Long clientId;

    @Column(name = "action", nullable = false, length = 100)
    private String action;

    /**
     * The mechanism used for this event, not the account's configured default. Null for
     * events where it is meaningless, such as logout.
     */
    @Column(name = "auth_provider", length = 50)
    private String authProvider;

    /**
     * The admin who caused this event, when that is not {@link #userId} itself. Populated
     * for {@link #ACTION_IMPERSONATION_STARTED}; null for ordinary self-service activity.
     */
    @Column(name = "actor_user_id")
    private Long actorUserId;

    /** Hashed servlet session id. Pairs a login with its logout to yield a duration. */
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

    /** Free-form JSON. Kept as TEXT rather than jsonb: nothing queries inside it yet. */
    @Column(name = "metadata", columnDefinition = "TEXT")
    private String metadata;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
