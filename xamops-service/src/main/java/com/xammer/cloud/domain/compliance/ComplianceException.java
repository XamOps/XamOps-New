package com.xammer.cloud.domain.compliance;

import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * A time-boxed, justified acceptance of a failing control.
 *
 * Keyed to account + check rather than to a scan, so it survives re-scanning.
 * {@code expiresAt} is mandatory: an exception with no expiry is how an organisation
 * quietly accepts a risk forever, and it is the first thing an auditor asks about.
 */
@Entity
@Data
@NoArgsConstructor
@Table(name = "compliance_exception")
public class ComplianceException {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String EXPIRED = "EXPIRED";
    public static final String REVOKED = "REVOKED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cloud_account_id", nullable = false)
    private String cloudAccountId;

    @Column(name = "check_id", nullable = false)
    private String checkId;

    /** Null waives the whole check; set it to waive a single resource. */
    @Column(name = "resource_uid")
    private String resourceUid;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String justification;

    @Column(name = "compensating_control", columnDefinition = "TEXT")
    private String compensatingControl;

    @Column(name = "requested_by", nullable = false)
    private String requestedBy;

    @Column(name = "approved_by")
    private String approvedBy;

    @Column(nullable = false)
    private String status = PENDING;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_reason", columnDefinition = "TEXT")
    private String revokedReason;

    /** True only while the waiver should actually suppress a failing control. */
    public boolean isActiveAt(Instant now) {
        return APPROVED.equals(status) && expiresAt != null && expiresAt.isAfter(now);
    }
}
