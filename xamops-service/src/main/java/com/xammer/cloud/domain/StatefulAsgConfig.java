package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;

/**
 * Per-ASG stateful workload configuration for Spot interruption protection.
 *
 * When statefulEnabled=true, the AutoSpotting service performs additional
 * protective actions during a Spot interruption 2-minute warning:
 *  1. EBS snapshot creation for non-root volumes
 *  2. Volume detach and tagging for reattachment to the replacement instance
 *  3. Connection draining via ELB target group deregistration
 *  4. Lifecycle hook for graceful shutdown on the TERMINATING transition
 */
@Entity
@Data
@NoArgsConstructor
@Table(name = "stateful_asg_config",
       uniqueConstraints = @UniqueConstraint(columnNames = {"account_id", "region", "asg_name"}))
public class StatefulAsgConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "stateful_asg_config_seq_gen")
    @SequenceGenerator(name = "stateful_asg_config_seq_gen", sequenceName = "stateful_asg_config_id_seq", allocationSize = 1)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private CloudAccount account;

    @Column(nullable = false)
    private String region;

    @Column(name = "asg_name", nullable = false)
    private String asgName;

    /** Whether stateful Spot protection is active for this ASG. */
    @Column(name = "stateful_enabled", nullable = false)
    private boolean statefulEnabled = false;

    /**
     * Workload type for informational/logging purposes.
     * Values: GENERIC, DATABASE, CACHE, MESSAGE_QUEUE
     */
    @Column(name = "stateful_type")
    private String statefulType = "GENERIC";

    /**
     * When true, non-root EBS volumes are detached from the interrupted instance
     * and tagged with reattachment metadata so the replacement instance (via user-data
     * or SSM) can pick them up automatically.
     */
    @Column(name = "preserve_ebs_volumes")
    private boolean preserveEbsVolumes = false;

    /**
     * When true, an asynchronous EBS snapshot is triggered for each non-root volume
     * as soon as the 2-minute Spot interruption warning is received.
     * Snapshots are tagged with spot-recovery-snapshot=true for easy identification.
     */
    @Column(name = "create_snapshot_on_interrupt")
    private boolean createSnapshotOnInterrupt = true;

    /**
     * When true, the instance is deregistered from its ALB/NLB target groups
     * so that in-flight connections can drain before the instance is reclaimed.
     * Works best when combined with ELB target group connection draining settings.
     */
    @Column(name = "enable_connection_drain")
    private boolean enableConnectionDrain = true;

    /**
     * Heartbeat timeout (seconds) for the EC2_INSTANCE_TERMINATING lifecycle hook.
     * Must be <= 120 s (the Spot interruption 2-minute window).
     * Default: 90 s gives the app time to flush state before ASG marks the hook complete.
     */
    @Column(name = "drain_timeout_seconds")
    private int drainTimeoutSeconds = 90;

    /**
     * When true, instance refresh uses 90% minHealthyPercentage and 600 s warmup
     * instead of the standard 80% / 300 s. This is safer for stateful apps
     * as it reduces the chance of simultaneous failures across instances.
     */
    @Column(name = "use_conservative_refresh")
    private boolean useConservativeRefresh = true;
}
