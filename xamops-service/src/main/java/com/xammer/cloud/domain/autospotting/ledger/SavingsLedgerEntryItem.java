package com.xammer.cloud.domain.autospotting.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/** Per-instance detail of one ledger entry; {@code instance_id} lives here (design-ledger.md §2.2). Append-only. */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "savings_ledger_entry_item")
@IdClass(SavingsLedgerEntryItemId.class)
public class SavingsLedgerEntryItem extends AppendOnlyEntity<SavingsLedgerEntryItemId> {

    @Id
    @Column(name = "entry_id", nullable = false)
    private UUID entryId;

    @Id
    @Column(name = "seq", nullable = false)
    private int seq;

    @Column(name = "instance_id", nullable = false, length = 200)
    private String instanceId;

    @Column(name = "interval_id", nullable = false)
    private UUID intervalId;

    @Column(name = "instance_type", nullable = false, length = 64)
    private String instanceType;

    @Column(name = "zone", nullable = false, length = 64)
    private String zone;

    @Column(name = "platform", nullable = false, length = 32)
    private String platform;

    @Column(name = "purchase_option", nullable = false, length = 12)
    private String purchaseOption;

    @Column(name = "capacity_weight", nullable = false, precision = 12, scale = 6)
    private BigDecimal capacityWeight;

    @Column(name = "seconds", nullable = false)
    private long seconds;

    @Column(name = "billed_seconds", nullable = false)
    private long billedSeconds;

    @Column(name = "counterfactual_unit_seconds", nullable = false, precision = 24, scale = 6)
    private BigDecimal counterfactualUnitSeconds;

    @Column(name = "od_rate", precision = 24, scale = 12)
    private BigDecimal odRate;

    @Column(name = "od_rate_snapshot_id")
    private UUID odRateSnapshotId;

    @Column(name = "od_rate_basis", length = 16)
    private String odRateBasis;

    @Column(name = "spot_rate", precision = 24, scale = 12)
    private BigDecimal spotRate;

    @Column(name = "spot_rate_snapshot_id")
    private UUID spotRateSnapshotId;

    @Column(name = "counterfactual_cost", nullable = false, precision = 24, scale = 10)
    private BigDecimal counterfactualCost;

    @Column(name = "spot_cost", nullable = false, precision = 24, scale = 10)
    private BigDecimal spotCost;

    @Column(name = "role", nullable = false, length = 20)
    private String role;

    @Override
    public SavingsLedgerEntryItemId getId() {
        return new SavingsLedgerEntryItemId(entryId, seq);
    }
}
