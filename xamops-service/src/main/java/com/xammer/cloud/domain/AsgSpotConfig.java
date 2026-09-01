package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;

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

    /**
     * Target percentage of Spot instances (0-100).
     * When set, AWS maintains this Spot percentage automatically.
     * Mutually exclusive with onDemandCount.
     * Example: spotPercentage=75 means 75% Spot, 25% On-Demand
     */
    @Column(name = "spot_percentage")
    private Integer spotPercentage;

    /**
     * Fixed number of On-Demand instances to maintain.
     * All instances above this count will be Spot.
     * Mutually exclusive with spotPercentage.
     * Example: onDemandCount=2 means always keep 2 On-Demand, rest are Spot
     */
    @Column(name = "on_demand_count")
    private Integer onDemandCount;
}
