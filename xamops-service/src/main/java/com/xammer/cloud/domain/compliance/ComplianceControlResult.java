package com.xammer.cloud.domain.compliance;

import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;

/**
 * The outcome of one control in one scan.
 *
 * check_id is Prowler's event_code (e.g. {@code iam_root_mfa_enabled}). It is stable
 * across Prowler releases in a way the finding description is not, which is why it —
 * and not a truncated description — is the identity of a control everywhere here.
 */
@Entity
@Data
@NoArgsConstructor
@Table(name = "compliance_control_result")
public class ComplianceControlResult {

    public static final String PASS = "PASS";
    public static final String FAIL = "FAIL";
    public static final String MANUAL = "MANUAL";
    /** Never persisted. Applied at read time when an approved exception covers the check. */
    public static final String WAIVED = "WAIVED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "scan_id", nullable = false)
    private Long scanId;

    @Column(name = "cloud_account_id", nullable = false)
    private String cloudAccountId;

    @Column(name = "check_id", nullable = false)
    private String checkId;

    @Column(name = "check_title", columnDefinition = "TEXT")
    private String checkTitle;

    @Column
    private String service;

    @Column
    private String severity;

    @Column(nullable = false)
    private String status;

    @Column(name = "passed_resources", nullable = false)
    private Integer passedResources = 0;

    @Column(name = "failed_resources", nullable = false)
    private Integer failedResources = 0;

    @Column(columnDefinition = "TEXT")
    private String remediation;

    /** Newline-separated reference URLs. */
    @Column(name = "remediation_refs", columnDefinition = "TEXT")
    private String remediationRefs;
}
