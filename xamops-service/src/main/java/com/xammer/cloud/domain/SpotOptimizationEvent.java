package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.Instant;

/**
 * SpotOptimizationEvent Entity
 *
 * Tracks every instance swap performed by the AutoSpotting engine for audit and
 * reporting.
 */
@Entity
@Data
@NoArgsConstructor
@Table(name = "spot_optimization_event")
public class SpotOptimizationEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "spot_event_seq_gen")
    @SequenceGenerator(name = "spot_event_seq_gen", sequenceName = "spot_event_id_seq", allocationSize = 1)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private CloudAccount account;

    @Column(name = "asg_name", nullable = false)
    private String asgName;

    @Column(nullable = false)
    private String region;

    @Column(name = "old_instance_id")
    private String oldInstanceId;

    @Column(name = "old_instance_type")
    private String oldInstanceType;

    @Column(name = "new_instance_id")
    private String newInstanceId;

    @Column(name = "new_instance_type")
    private String newInstanceType;

    @Column(name = "hourly_savings")
    private Double hourlySavings;

    @Column(name = "event_time", nullable = false)
    private Instant eventTime;

    @Column(nullable = false)
    private String status; // SUCCESS, FAILED

    @Column(columnDefinition = "TEXT")
    private String details;

    @PrePersist
    public void onCreate() {
        if (eventTime == null) {
            eventTime = Instant.now();
        }
    }
}
