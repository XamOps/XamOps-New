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

    // --- Risk and classification, from Upwind's inventory CATALOG asset (v2). These are the
    // fields the flat /inventory/assets/search never returned, which is why XamSecure
    // previously had no privilege or sensitive-data signal to show.

    /** high_privilege_risk.has_elevated_privilege — drives the Identities privilege view. */
    @Column(name = "has_elevated_privilege")
    private Boolean hasElevatedPrivilege;

    /** sensitive_data_at_rest.{has_pii,has_phi,has_pci} */
    @Column(name = "sensitive_pii_at_rest")
    private Boolean sensitivePiiAtRest;

    @Column(name = "sensitive_phi_at_rest")
    private Boolean sensitivePhiAtRest;

    @Column(name = "sensitive_pci_at_rest")
    private Boolean sensitivePciAtRest;

    /** sensitive_data_in_transit.{has_pii,has_phi,has_pci} */
    @Column(name = "sensitive_pii_in_transit")
    private Boolean sensitivePiiInTransit;

    @Column(name = "sensitive_phi_in_transit")
    private Boolean sensitivePhiInTransit;

    @Column(name = "sensitive_pci_in_transit")
    private Boolean sensitivePciInTransit;

    /** vulnerability_risk counts, as Upwind computed them for this asset. */
    @Column(name = "vuln_critical_count")
    private Integer vulnCriticalCount;

    @Column(name = "vuln_high_count")
    private Integer vulnHighCount;

    @Column(name = "vuln_medium_count")
    private Integer vulnMediumCount;

    @Column(name = "vuln_low_count")
    private Integer vulnLowCount;

    @Column(name = "vuln_total_count")
    private Integer vulnTotalCount;

    /** detection_risk counts. */
    @Column(name = "detection_critical_count")
    private Integer detectionCriticalCount;

    @Column(name = "detection_high_count")
    private Integer detectionHighCount;

    @Column(name = "detection_total_count")
    private Integer detectionTotalCount;

    /**
     * network_risk.internet_exposure_type, e.g. "wide".
     *
     * The only subfield of network_risk carrying values for our org: measured 2026-08-11, 137
     * assets had it (e.g. "wide") and every other network_risk subfield was empty everywhere —
     * including lastInternetCommunication below, which is mapped but has never had a value. The
     * remaining documented timestamps (last access from internet, IMDSv1/v2, last database
     * communication) are deliberately not added as columns that would always be null.
     */
    @Column(name = "internet_exposure_type")
    private String internetExposureType;

    /** network_risk.last_communication_with_internet_time. */
    @Column(name = "last_internet_communication")
    private Instant lastInternetCommunication;

    /**
     * technologies[] flattened to "name version" entries, plus an EOL/EOS marker when
     * Upwind dated one. Stored denormalised because it is only ever read as a display list.
     */
    @Column(name = "technologies", length = 2048)
    private String technologies;

    /** True when any technology on the asset is past its end-of-life or end-of-support date. */
    @Column(name = "has_eol_technology")
    private Boolean hasEolTechnology;

    /** Comma-joined private IPs from {@code private_ip_addresses}. */
    @Column(name = "private_ip_addresses", length = 512)
    private String privateIpAddresses;

    /** Comma-joined public IPs from {@code public_ip_addresses}. Presence is itself a signal. */
    @Column(name = "public_ip_addresses", length = 512)
    private String publicIpAddresses;

    /** Which Upwind mechanism covers this asset, from {@code protected_by}. */
    @Column(name = "protected_by", length = 128)
    private String protectedBy;

    /** Cloud provider tags, comma-joined, from {@code tags}. */
    @Column(name = "cloud_tags", length = 1024)
    private String cloudTags;

    @Column(name = "raw_payload", columnDefinition = "TEXT")
    private String rawPayload;

    @Column(name = "synced_at", nullable = false)
    private Instant syncedAt;
}
