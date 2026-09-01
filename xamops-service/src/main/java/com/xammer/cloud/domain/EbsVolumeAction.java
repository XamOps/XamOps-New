package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Audit record for every disk optimization action taken by XamOps.
 */
@Entity
@Data
@NoArgsConstructor
@Table(name = "ebs_volume_action")
public class EbsVolumeAction {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "ebs_action_seq_gen")
    @SequenceGenerator(name = "ebs_action_seq_gen", sequenceName = "ebs_action_id_seq", allocationSize = 1)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private CloudAccount account;

    @Column(name = "volume_id", nullable = false)
    private String volumeId;

    @Column(name = "region", nullable = false)
    private String region;

    /**
     * Action type: UPGRADE_TYPE | DELETE_UNATTACHED | EXPAND | SHRINK_INITIATED
     */
    @Column(name = "action_type", nullable = false)
    private String actionType;

    @Column(name = "old_type")
    private String oldType;

    @Column(name = "new_type")
    private String newType;

    @Column(name = "old_size_gb")
    private Integer oldSizeGb;

    @Column(name = "new_size_gb")
    private Integer newSizeGb;

    /** Estimated monthly savings in USD */
    @Column(name = "estimated_savings")
    private Double estimatedSavings;

    /** PENDING | IN_PROGRESS | SUCCESS | FAILED */
    @Column(name = "status", nullable = false)
    private String status = "PENDING";

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "initiated_at", nullable = false)
    private Instant initiatedAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;
}
