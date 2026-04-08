package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * Stores a Google Workspace connection for a XamOps client/tenant.
 * One connection per client (unique constraint on client_id).
 *
 * The service account key JSON is stored as-is; encrypt at the DB level
 * or via a KMS/Vault integration for production hardening.
 */
@Entity
@Table(name = "workspace_connection")
@Data
@NoArgsConstructor
public class WorkspaceConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The XamOps client (tenant) this connection belongs to. One workspace per client. */
    @Column(name = "client_id", nullable = false, unique = true)
    private Long clientId;

    /** Primary domain of the Google Workspace org, e.g. "company.com". */
    @Column(nullable = false)
    private String domain;

    /** Admin email used for service-account impersonation (domain-wide delegation). */
    @Column(name = "admin_email", nullable = false)
    private String adminEmail;

    /** Service account key JSON (full JSON from GCP, stored as TEXT). */
    @Column(name = "service_account_key", columnDefinition = "TEXT", nullable = false)
    private String serviceAccountKey;

    /** CONNECTED | DISCONNECTED | ERROR */
    @Column(nullable = false)
    private String status = "DISCONNECTED";

    /** Last error message when status = ERROR. */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /** Cached counts from last sync — updated by WorkspaceService.syncUsers(). */
    @Column(name = "total_users")
    private Integer totalUsers;

    @Column(name = "active_users")
    private Integer activeUsers;

    @Column(name = "inactive_users")
    private Integer inactiveUsers;

    @Column(name = "suspended_users")
    private Integer suspendedUsers;

    @Column(name = "last_synced_at")
    private LocalDateTime lastSyncedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
