package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * A Google Workspace user record synced from the Admin Directory API.
 * Rows are deleted and re-inserted on every sync (upsert via deleteByConnectionId + batch insert).
 */
@Entity
@Table(name = "workspace_user",
       indexes = {
           @Index(name = "idx_ws_user_connection", columnList = "connection_id"),
           @Index(name = "idx_ws_user_email",      columnList = "primary_email")
       })
@Data
@NoArgsConstructor
public class WorkspaceUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "connection_id", nullable = false)
    private Long connectionId;

    @Column(name = "primary_email", nullable = false)
    private String primaryEmail;

    @Column(name = "full_name")
    private String fullName;

    @Column(name = "org_unit_path")
    private String orgUnitPath;

    /** Suspended in Google Workspace admin console. */
    @Column(name = "is_suspended", nullable = false)
    private boolean suspended = false;

    /** Has super-admin role in Google Workspace. */
    @Column(name = "is_admin", nullable = false)
    private boolean admin = false;

    /** UTC timestamp of last Google Workspace login, null = never logged in. */
    @Column(name = "last_login_time")
    private LocalDateTime lastLoginTime;

    /** Google Workspace account creation timestamp. */
    @Column(name = "created_time")
    private LocalDateTime createdTime;

    /**
     * Days elapsed since lastLoginTime at sync time.
     * null when the user has never logged in (treated as worst case = infinite).
     */
    @Column(name = "days_since_login")
    private Integer daysSinceLogin;

    /** ACTIVE | INACTIVE | SUSPENDED — derived during sync. */
    @Column(name = "user_status", nullable = false)
    private String userStatus = "ACTIVE";

    @Column(name = "synced_at", nullable = false)
    private LocalDateTime syncedAt = LocalDateTime.now();
}
