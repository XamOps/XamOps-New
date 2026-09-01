package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Tracks per-volume alert state for deduplication.
 * One row per (account, volume, alert_type). resolved_at IS NULL = active alert.
 */
@Entity
@Data
@NoArgsConstructor
@Table(name = "disk_alert_state",
       uniqueConstraints = @UniqueConstraint(columnNames = {"account_id", "volume_id", "alert_type"}))
public class DiskAlertState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private CloudAccount account;

    @Column(name = "volume_id", nullable = false, length = 100)
    private String volumeId;

    @Column(name = "region", nullable = false, length = 50)
    private String region;

    /** NEAR_FULL | UNDERUTILIZED */
    @Column(name = "alert_type", nullable = false, length = 20)
    private String alertType;

    @Column(name = "alerted_at", nullable = false)
    private Instant alertedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;
}
