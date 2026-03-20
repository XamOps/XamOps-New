package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.Instant;

/**
 * Disk rightsizing policy — defines thresholds and target type for auto-action.
 */
@Entity
@Data
@NoArgsConstructor
@Table(name = "disk_policy",
       uniqueConstraints = @UniqueConstraint(columnNames = {"account_id", "policy_name"}))
public class DiskPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "disk_policy_seq_gen")
    @SequenceGenerator(name = "disk_policy_seq_gen", sequenceName = "disk_policy_id_seq", allocationSize = 1)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private CloudAccount account;

    @Column(name = "policy_name", nullable = false)
    private String policyName;

    /** Target utilization % — expand when above, flag when below */
    @Column(name = "desired_utilization", nullable = false)
    private int desiredUtilization = 75;

    /** Minimum disk size in GB — never shrink below this */
    @Column(name = "min_disk_size_gb", nullable = false)
    private int minDiskSizeGb = 10;

    /** Maximum number of disks in pool — used for auto-expand pooling */
    @Column(name = "max_disk_count", nullable = false)
    private int maxDiskCount = 10;

    /**
     * Policy type: STANDARD | IOPS_OPTIMIZED | CUSTOM
     */
    @Column(name = "policy_type", nullable = false)
    private String policyType = "STANDARD";

    /**
     * Target disk type for CUSTOM policy (gp2, gp3, io1, io2, sc1, st1).
     * Null for STANDARD/IOPS_OPTIMIZED.
     */
    @Column(name = "target_disk_type")
    private String targetDiskType;

    /** Cloud provider: AWS | GCP | AZURE */
    @Column(name = "cloud_provider", nullable = false)
    private String cloudProvider = "AWS";

    @Column(name = "is_default", nullable = false)
    private boolean isDefault = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt;
}
