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

    public AutoSpottingSettings(CloudAccount account) {
        this.account = account;
        this.accountId = account.getId();
    }
}
