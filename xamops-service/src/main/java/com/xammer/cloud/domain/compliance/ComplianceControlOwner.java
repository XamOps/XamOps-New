package com.xammer.cloud.domain.compliance;

import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.Instant;

/**
 * Who is accountable for a control. A standing fact about the control, not a property
 * of any one scan, so it is keyed to account + check and survives re-scanning.
 */
@Entity
@Data
@NoArgsConstructor
@Table(name = "compliance_control_owner")
public class ComplianceControlOwner {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cloud_account_id", nullable = false)
    private String cloudAccountId;

    @Column(name = "check_id", nullable = false)
    private String checkId;

    @Column(name = "owner_name")
    private String ownerName;

    @Column(name = "owner_email")
    private String ownerEmail;

    @Column
    private String team;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private String updatedBy;
}
