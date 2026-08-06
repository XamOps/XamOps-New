package com.xammer.cloud.domain.compliance;

import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.Instant;

/**
 * One compliance scan run. Immutable once COMPLETED.
 *
 * The regions and frameworks that were in scope are recorded on the row rather than
 * read from config at display time, because a score is only comparable across scans
 * that covered the same ground. A run that scanned one region is not a worse result
 * than a run that scanned four — it is a different question.
 */
@Entity
@Data
@NoArgsConstructor
@Table(name = "compliance_scan")
public class ComplianceScan {

    public static final String RUNNING = "RUNNING";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cloud_account_id", nullable = false)
    private String cloudAccountId;

    /** AWS | GCP | Azure */
    @Column(nullable = false)
    private String provider;

    @Column(nullable = false)
    private String status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    /** Comma-separated region list actually passed to the scanner. */
    @Column(columnDefinition = "TEXT")
    private String regions;

    /** Comma-separated framework ids actually requested. */
    @Column(columnDefinition = "TEXT")
    private String frameworks;

    @Column(name = "prowler_version")
    private String prowlerVersion;

    @Column(name = "total_findings", nullable = false)
    private Integer totalFindings = 0;

    @Column(name = "passed_findings", nullable = false)
    private Integer passedFindings = 0;

    @Column(name = "failed_findings", nullable = false)
    private Integer failedFindings = 0;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /**
     * Severity-weighted score at scan time, denormalised so the trend chart is one
     * indexed read. Null when the scan produced nothing scoreable.
     */
    @Column
    private Integer score;
}
