package com.xammer.cloud.domain.autospotting;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * An account allowed to convert while allowlist-only is on (design-core.md §6.1, table
 * {@code autospotting_allowlist}), keyed by {@code cloud_account_id}. {@code ck_asal_basis} ties the
 * {@code billing_basis} to {@code contract_id} (CONTRACT requires one, NO_FEE forbids one). Added
 * from B4 once {@code autospotting_contract} exists; no conversion flag lifts before then.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "autospotting_allowlist")
public class AutoSpottingAllowlistEntry {

    @Id
    @Column(name = "cloud_account_id", nullable = false)
    private Long cloudAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 8)
    private Provider provider;

    @Column(name = "billing_basis", nullable = false, length = 12)
    private String billingBasis;

    @Column(name = "contract_id")
    private UUID contractId;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Column(name = "added_by", nullable = false, length = 160)
    private String addedBy;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt;
}
