package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One live security finding, one row.
 *
 * <p>
 * These arrive over the same EventBridge stream that feeds the inventory plane. The
 * {@code xamops-inventory-security} rule has been forwarding Security Hub, GuardDuty and
 * Config events with their {@code detail} intact since the stack shipped, and
 * {@code InventoryEventListener} was deleting them unread — they carry no {@code arns}, so
 * they fell through the empty-ARN branch. This is where they land instead.
 *
 * <p>
 * Kept apart from {@code compliance_finding} deliberately. That entity belongs to a
 * {@code compliance_scan} row and represents a point-in-time Prowler result; these have no
 * scan to belong to and are identified by the provider's own finding id, so a redelivery
 * updates a row rather than adding one.
 *
 * @see com.xammer.cloud.service.security.SecurityFindingIngestService
 */
@Entity
@Table(name = "cloud_security_finding")
@Data
@NoArgsConstructor
public class CloudSecurityFinding {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_RESOLVED = "RESOLVED";
    public static final String STATUS_SUPPRESSED = "SUPPRESSED";

    public static final String SOURCE_SECURITY_HUB = "SECURITYHUB";
    public static final String SOURCE_GUARDDUTY = "GUARDDUTY";
    public static final String SOURCE_CONFIG = "CONFIG";
    /** Derived by XamOps from a CloudTrail lifecycle event rather than by AWS. */
    public static final String SOURCE_XAMOPS_DETECTOR = "XAMOPS_DETECTOR";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cloud_account_id", nullable = false)
    private Long cloudAccountId;

    /** Denormalised from {@link CloudAccount}; read paths arrive holding this, not our row id. */
    @Column(name = "aws_account_id", nullable = false, length = 32)
    private String awsAccountId;

    /**
     * The provider's finding id, or a deterministic hash for findings XamOps derives.
     *
     * <p>
     * Unique per connection together with {@code cloudAccountId}. This is what makes
     * ingest idempotent: SQS is at-least-once, GuardDuty re-publishes subsequent
     * occurrences of the same finding on its own cadence, and Security Hub re-imports on
     * every state change. Keying on the provider id turns all three into updates.
     */
    @Column(name = "finding_id", nullable = false, length = 512)
    private String findingId;

    @Column(name = "source", nullable = false, length = 32)
    private String source;

    /** Normalised; see {@link com.xammer.cloud.service.security.SecuritySeverity}. */
    @Column(name = "severity", nullable = false, length = 16)
    private String severity;

    @Column(name = "title", nullable = false, length = 1024)
    private String title;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "resource_arn", length = 2048)
    private String resourceArn;

    @Column(name = "resource_type", length = 256)
    private String resourceType;

    @Column(name = "region", length = 64)
    private String region;

    @Column(name = "status", nullable = false, length = 16)
    private String status = STATUS_ACTIVE;

    @Column(name = "compliance_framework", length = 256)
    private String complianceFramework;

    @Column(name = "control_id", length = 256)
    private String controlId;

    @Column(name = "remediation", columnDefinition = "text")
    private String remediation;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /**
     * When a notification last went out for this finding.
     *
     * <p>
     * Null means never notified. The notifier gates on this column rather than on
     * {@code AlertNotificationDeduplicator}'s content fingerprint, which joins
     * {@code type|name|service|region|status} and so ignores severity entirely — it cannot
     * express "notify me only about criticals".
     */
    @Column(name = "notified_at")
    private Instant notifiedAt;

    /** The original event detail as text; see the migration for why not jsonb. */
    @Column(name = "raw_detail", columnDefinition = "text")
    private String rawDetail;

    /** When AWS says the thing happened, as opposed to when we stored it. */
    @Column(name = "event_time")
    private Instant eventTime;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
