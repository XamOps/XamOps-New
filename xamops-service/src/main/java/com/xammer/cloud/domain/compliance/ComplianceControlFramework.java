package com.xammer.cloud.domain.compliance;

import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;

/**
 * Maps one check to one framework control, per scan.
 *
 * Many-to-many on purpose: "root account has MFA" satisfies CIS 1.5, SOC 2 CC6.1 and
 * ISO A.9.2 simultaneously. Storing it this way is what makes "fixing this one check
 * moves four frameworks" a query rather than a guess.
 */
@Entity
@Data
@NoArgsConstructor
@Table(name = "compliance_control_framework")
public class ComplianceControlFramework {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "scan_id", nullable = false)
    private Long scanId;

    @Column(name = "cloud_account_id", nullable = false)
    private String cloudAccountId;

    @Column(name = "check_id", nullable = false)
    private String checkId;

    /** Raw framework id as Prowler reports it, e.g. "CIS-3.0" or "SOC2". */
    @Column(nullable = false)
    private String framework;

    /** The control reference inside that framework, e.g. "1.5" or "CC6.1". */
    @Column(name = "framework_control", nullable = false)
    private String frameworkControl;

    @Column(nullable = false)
    private String status;
}
