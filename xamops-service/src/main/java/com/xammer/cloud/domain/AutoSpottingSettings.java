package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;

/**
 * AutoSpottingSettings Entity
 *
 * Stores account-specific configurations for AutoSpotting automation.
 */
@Entity
@Data
@NoArgsConstructor
@Table(name = "autospotting_settings")
public class AutoSpottingSettings {

    @Id
    @Column(name = "account_id")
    private Long accountId;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", insertable = false, updatable = false)
    private CloudAccount account;

    @Column(nullable = false)
    private boolean enabled = false;

    @Column(name = "tag_filters")
    private String tagFilters = "spot-enabled=true";

    @Column(name = "excluded_instances", columnDefinition = "TEXT")
    private String excludedInstances;

    @Column(name = "max_spot_percentage")
    private int maxSpotPercentage = 100;

    @Column(name = "min_on_demand_number")
    private int minOnDemandNumber = 0;

    /**
     * NEW FEATURE: Respect RI/SP Commitments
     * When enabled, AutoSpotting will check RI/SP utilization before converting to Spot.
     * Only converts to Spot when RI utilization >= riSpUtilizationThreshold.
     * Default: false (disabled - existing behavior unchanged)
     */
    @Column(name = "respect_ri_sp_commitments")
    private boolean respectRiSpCommitments = false;

    /**
     * RI/SP utilization threshold percentage (0-100)
     * Only convert to Spot when RI+SP utilization >= this threshold
     * Default: 90%
     */
    @Column(name = "ri_sp_utilization_threshold")
    private int riSpUtilizationThreshold = 90;

    public AutoSpottingSettings(CloudAccount account) {
        this.account = account;
        this.accountId = account.getId();
    }
}
