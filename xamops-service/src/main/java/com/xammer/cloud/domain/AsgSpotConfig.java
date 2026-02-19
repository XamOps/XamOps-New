package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;

/**
 * ASG-specific Spot configuration
 *
 * Stores per-ASG instance type preferences and Spot settings
 */
@Entity
@Data
@NoArgsConstructor
@Table(name = "asg_spot_config",
       uniqueConstraints = @UniqueConstraint(columnNames = {"account_id", "region", "asg_name"}))
public class AsgSpotConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "asg_spot_config_seq_gen")
    @SequenceGenerator(name = "asg_spot_config_seq_gen", sequenceName = "asg_spot_config_id_seq", allocationSize = 1)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private CloudAccount account;

    @Column(nullable = false)
    private String region;

    @Column(name = "asg_name", nullable = false)
    private String asgName;

    /**
     * Comma-separated list of allowed instance types for Spot diversification.
     * Example: "t3.micro,t3a.micro,t3.small"
     *
     * When the primary instance type has no Spot capacity, these alternatives will be tried.
     */
    @Column(name = "allowed_instance_types", columnDefinition = "TEXT")
    private String allowedInstanceTypes;

    /**
     * Spot allocation strategy: "lowest-price", "capacity-optimized", "capacity-optimized-prioritized"
     */
    @Column(name = "spot_allocation_strategy")
    private String spotAllocationStrategy = "capacity-optimized";
}
