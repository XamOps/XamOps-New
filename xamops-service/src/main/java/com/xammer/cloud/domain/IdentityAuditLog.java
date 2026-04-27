package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * Immutable audit trail for all identity management actions.
 *
 * Written BEFORE the corresponding Authentik API call so that even a failed
 * call leaves an attempted-action record. The metadata column stores additional
 * context (e.g. Authentik error response) as a JSON string.
 *
 * Actions: USER_INVITED, USER_DEACTIVATED, USER_ADDED_TO_GROUP,
 *          USER_REMOVED_FROM_GROUP, TENANT_PROVISIONED.
 *
 * Per Authentik best practice, users are never deleted — only deactivated.
 * This preserves the audit trail for offboarded users.
 */
@Entity
@Table(name = "identity_audit_log")
@Data
@NoArgsConstructor
public class IdentityAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    /** XamOps User.id of the admin who performed the action. */
    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "action", nullable = false, length = 100)
    private String action;

    @Column(name = "target_user_email", length = 255)
    private String targetUserEmail;

    @Column(name = "group_name", length = 255)
    private String groupName;

    /** JSON blob for extra context: Authentik user pk, error message, etc. */
    @Column(name = "metadata", columnDefinition = "TEXT")
    private String metadata;

    @Column(name = "timestamp", nullable = false)
    private LocalDateTime timestamp = LocalDateTime.now();
}
