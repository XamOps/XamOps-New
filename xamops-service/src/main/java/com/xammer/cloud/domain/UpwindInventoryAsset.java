package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Data
@NoArgsConstructor
@Table(name = "upwind_inventory_asset")
public class UpwindInventoryAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "upwind_id", nullable = false, unique = true)
    private String upwindId;

    @Column(name = "asset_category")
    private String assetCategory;

    @Column(name = "asset_sub_category")
    private String assetSubCategory;

    private String label;

    @Column(name = "cloud_provider")
    private String cloudProvider;

    @Column(name = "cloud_account_id")
    private String cloudAccountId;

    @Column(name = "cloud_resource_id", length = 512)
    private String cloudResourceId;

    private String name;
    private String region;

    /** ACTIVE / RESOLVED (absent from latest sync) */
    @Column(nullable = false)
    private String status = "ACTIVE";

    @Column(name = "raw_payload", columnDefinition = "TEXT")
    private String rawPayload;

    @Column(name = "synced_at", nullable = false)
    private Instant syncedAt;
}
