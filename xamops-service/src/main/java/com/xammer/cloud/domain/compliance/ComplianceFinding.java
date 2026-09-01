package com.xammer.cloud.domain.compliance;

import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Resource-level evidence for one control in one scan.
 *
 * Only FAIL and MANUAL findings are stored. Passing resources are the overwhelming
 * majority of scanner output and the least-asked-for evidence, so their count lives on
 * {@link ComplianceControlResult#getPassedResources()} instead of one row each. If a
 * customer ever needs the passing resource list, that is a deliberate schema change,
 * not something to absorb silently into a table that would grow by ~50k rows a scan.
 */
@Entity
@Data
@NoArgsConstructor
@Table(name = "compliance_finding")
public class ComplianceFinding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "scan_id", nullable = false)
    private Long scanId;

    @Column(name = "cloud_account_id", nullable = false)
    private String cloudAccountId;

    @Column(name = "check_id", nullable = false)
    private String checkId;

    @Column(nullable = false)
    private String status;

    @Column
    private String severity;

    @Column(name = "resource_uid")
    private String resourceUid;

    @Column(name = "resource_name")
    private String resourceName;

    @Column(name = "resource_type")
    private String resourceType;

    @Column
    private String region;

    /** Prowler's status_extended — the closest thing to a captured observation. */
    @Column(name = "status_detail", columnDefinition = "TEXT")
    private String statusDetail;

    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;
}
