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
 * A user's assertion that a finding has been fixed, one row.
 *
 * <p>
 * {@code ExposureDetector} has always ended its remediation advice with "XamOps sees the change
 * that opened this but not the one that closes it, so mark the finding resolved once you have
 * verified the fix". Nothing in the product could mark anything resolved, so that sentence
 * described an action the user could not take — and a resource fixed silently stayed ACTIVE for
 * as long as the connection existed. This is the record of that action.
 *
 * <p>
 * <b>Keyed on the finding's natural identity, not on a row id.</b> The Security Center page
 * renders three kinds of finding side by side: event-driven rows from
 * {@link CloudSecurityFinding}, Prowler results, and {@code SecurityService}'s describe-based
 * checks. Only the first has a row to update. Keying on {@code resourceId|controlId} — the same
 * pair {@code SecurityFindingProjectionService.dedupKey} already treats as one finding's identity
 * — is what makes a single dismissal work on all three rather than appearing to work and
 * silently doing nothing on two of them.
 *
 * <p>
 * <b>Not a suppression, and deliberately not {@code ComplianceException}.</b> This says the
 * problem is gone; a waiver says the problem is real and accepted. The difference shows up in the
 * lifecycle: a waiver expires on a date someone approved, while this is retired the moment
 * evidence contradicts it — see {@link #getRetiredReason()}. It also means a resolution never
 * moves a compliance score, because a claim that has not been checked yet has not earned the
 * right to.
 *
 * @see com.xammer.cloud.service.security.SecurityFindingResolutionService
 */
@Entity
@Table(name = "security_finding_resolution")
@Data
@NoArgsConstructor
public class SecurityFindingResolution {

    /** The provider re-reported the finding over the event stream, so it is not fixed. */
    public static final String RETIRED_REOPENED_BY_PROVIDER = "REOPENED_BY_PROVIDER";
    /** A fresh scan still found the same problem, so it is not fixed. */
    public static final String RETIRED_STILL_PRESENT_IN_SCAN = "STILL_PRESENT_IN_SCAN";
    /** The user undid their own dismissal. */
    public static final String RETIRED_RESTORED_BY_USER = "RESTORED_BY_USER";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cloud_account_id", nullable = false)
    private Long cloudAccountId;

    /**
     * Hash of {@code resourceId|controlId}; see
     * {@link com.xammer.cloud.service.security.SecurityFindingKey}.
     *
     * <p>
     * Hashed because the literal key reaches 2304 characters and a btree index entry cannot hold
     * that — indexing the raw string would fail at insert on exactly the long ARNs a real estate
     * produces.
     */
    @Column(name = "finding_key_hash", nullable = false, length = 64)
    private String findingKeyHash;

    @Column(name = "resource_id", length = 2048)
    private String resourceId;

    @Column(name = "control_id", length = 256)
    private String controlId;

    /** Copied from the finding so the audit trail survives it. */
    @Column(name = "severity", length = 16)
    private String severity;

    @Column(name = "title", length = 1024)
    private String title;

    @Column(name = "resolved_by", nullable = false, length = 255)
    private String resolvedBy;

    @Column(name = "resolved_at", nullable = false)
    private Instant resolvedAt = Instant.now();

    @Column(name = "note", columnDefinition = "text")
    private String note;

    /**
     * When this claim stopped being believed. Null means it is live.
     *
     * <p>
     * Retired rather than deleted: "this was marked fixed on Tuesday and came back on Thursday"
     * is the most useful thing this table can tell anyone, and a delete would lose it.
     */
    @Column(name = "retired_at")
    private Instant retiredAt;

    @Column(name = "retired_reason", length = 64)
    private String retiredReason;

    public boolean isLive() {
        return retiredAt == null;
    }
}
