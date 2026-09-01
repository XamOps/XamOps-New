package com.xammer.cloud.domain.compliance;

import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Work tracked against a failing control.
 *
 * {@code verifiedAt} and {@code verifiedScanId} are the point of this table: they record
 * that a later scan actually observed the control passing. RESOLVED is a claim, VERIFIED
 * is evidence, and only the second one is worth showing an auditor.
 */
@Entity
@Data
@NoArgsConstructor
@Table(name = "compliance_remediation")
public class ComplianceRemediation {

    public static final String OPEN = "OPEN";
    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String RESOLVED = "RESOLVED";
    public static final String VERIFIED = "VERIFIED";
    public static final String WONT_FIX = "WONT_FIX";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cloud_account_id", nullable = false)
    private String cloudAccountId;

    @Column(name = "check_id", nullable = false)
    private String checkId;

    @Column(name = "resource_uid")
    private String resourceUid;

    @Column(nullable = false)
    private String status = OPEN;

    @Column(name = "assignee_name")
    private String assigneeName;

    @Column(name = "assignee_email")
    private String assigneeEmail;

    /** External tracker reference, e.g. a Jira key or BillOps ticket id. */
    @Column(name = "ticket_ref")
    private String ticketRef;

    @Column(name = "due_at")
    private Instant dueAt;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "verified_scan_id")
    private Long verifiedScanId;
}
