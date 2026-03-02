package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;

/**
 * VMSS-specific Spot configuration for Azure AutoSpotting
 *
 * Stores per-VMSS VM SKU preferences and Spot ratio settings.
 * Azure equivalent of AsgSpotConfig for AWS.
 */
@Entity
@Data
@NoArgsConstructor
@Table(name = "vmss_spot_config",
       uniqueConstraints = @UniqueConstraint(columnNames = {"account_id", "resource_group", "vmss_name"}))
public class VmssSpotConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "vmss_spot_config_seq_gen")
    @SequenceGenerator(name = "vmss_spot_config_seq_gen", sequenceName = "vmss_spot_config_id_seq", allocationSize = 1)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private CloudAccount account;

    @Column(name = "resource_group", nullable = false)
    private String resourceGroup;

    @Column(name = "vmss_name", nullable = false)
    private String vmssName;

    @Column(nullable = false)
    private String region;

    /**
     * Comma-separated list of allowed VM SKUs for Spot diversification.
     * Example: "Standard_D2s_v5,Standard_D4s_v5,Standard_E2s_v5"
     *
     * When the primary VM SKU has no Spot capacity, these alternatives will be tried.
     */
    @Column(name = "allowed_vm_skus", columnDefinition = "TEXT")
    private String allowedVmSkus;

    /**
     * Spot allocation strategy: "spot-only", "mixed" (default), "capacity-optimized"
     */
    @Column(name = "spot_allocation_strategy")
    private String spotAllocationStrategy = "mixed";

    /**
     * Target percentage of Spot instances (0-100).
     * When set, AutoSpotting maintains this Spot percentage during staged replacement.
     * Mutually exclusive with onDemandCount.
     * Example: spotPercentage=75 means 75% Spot, 25% Regular
     */
    @Column(name = "spot_percentage")
    private Integer spotPercentage;

    /**
     * Fixed number of Regular (on-demand) instances to maintain.
     * All instances above this count will be converted to Spot.
     * Mutually exclusive with spotPercentage.
     * Example: onDemandCount=1 means always keep 1 Regular, rest are Spot
     */
    @Column(name = "on_demand_count")
    private Integer onDemandCount;
}
