package com.xammer.cloud.domain.autospotting.ledger;

import com.xammer.cloud.domain.autospotting.Provider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One group-day of measured savings, or one adjustment (design-ledger.md §2.2). Every amount is a
 * {@link BigDecimal} at scale 10 ({@code NUMERIC(24,10)}); {@code netSavings} may be negative and is
 * clamped only on the statement line (PO-26). Append-only; nothing writes entries until the ledger
 * writer ships (the writer flags stay false).
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "savings_ledger_entry")
public class SavingsLedgerEntry extends AppendOnlyEntity<UUID> {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "run_id")
    private UUID runId;

    @Column(name = "entry_type", nullable = false, length = 12)
    private String entryType;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 8)
    private Provider provider;

    @Column(name = "cloud_account_id", nullable = false)
    private Long cloudAccountId;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    @Column(name = "group_id", nullable = false)
    private UUID groupId;

    @Column(name = "period_start", nullable = false)
    private Instant periodStart;

    @Column(name = "period_end", nullable = false)
    private Instant periodEnd;

    @Column(name = "statement_month", nullable = false)
    private LocalDate statementMonth;

    @Column(name = "calc_version", nullable = false, length = 16)
    private String calcVersion;

    @Column(name = "tier", nullable = false, length = 20)
    private String tier;

    @Column(name = "reconciliation_status", nullable = false, length = 20)
    private String reconciliationStatus;

    @Column(name = "reconciled_at")
    private Instant reconciledAt;

    @Column(name = "billable", nullable = false)
    private boolean billable;

    @Column(name = "non_billable_reason", length = 48)
    private String nonBillableReason;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "unknown_inputs", nullable = false, columnDefinition = "text[]")
    private String[] unknownInputs = new String[0];

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3, columnDefinition = "CHAR(3)")
    private String currency;

    @Column(name = "instance_seconds", nullable = false)
    private long instanceSeconds;

    @Column(name = "counterfactual_unit_seconds", nullable = false, precision = 24, scale = 6)
    private BigDecimal counterfactualUnitSeconds;

    @Column(name = "counterfactual_list_cost", nullable = false, precision = 24, scale = 10)
    private BigDecimal counterfactualListCost;

    @Column(name = "effective_rate_adjustment", nullable = false, precision = 24, scale = 10)
    private BigDecimal effectiveRateAdjustment;

    @Column(name = "counterfactual_cost", nullable = false, precision = 24, scale = 10)
    private BigDecimal counterfactualCost;

    @Column(name = "spot_cost", nullable = false, precision = 24, scale = 10)
    private BigDecimal spotCost;

    @Column(name = "gross_savings", nullable = false, precision = 24, scale = 10)
    private BigDecimal grossSavings;

    @Column(name = "commitment_deduction", nullable = false, precision = 24, scale = 10)
    private BigDecimal commitmentDeduction;

    @Column(name = "net_savings", nullable = false, precision = 24, scale = 10)
    private BigDecimal netSavings;

    @Column(name = "net_savings_lower_bound", precision = 24, scale = 10)
    private BigDecimal netSavingsLowerBound;

    @Column(name = "spot_cost_source", nullable = false, length = 24)
    private String spotCostSource;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "price_snapshot_ids", nullable = false, columnDefinition = "uuid[]")
    private UUID[] priceSnapshotIds = new UUID[0];

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "bill_import_ids", nullable = false, columnDefinition = "uuid[]")
    private UUID[] billImportIds = new UUID[0];

    @Column(name = "contract_id")
    private UUID contractId;

    @Column(name = "fx_rate", precision = 24, scale = 10)
    private BigDecimal fxRate;

    @Column(name = "fx_source", length = 64)
    private String fxSource;

    @Column(name = "fx_at")
    private Instant fxAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "exclusions", nullable = false, columnDefinition = "jsonb")
    private String exclusions;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "inputs", nullable = false, columnDefinition = "jsonb")
    private String inputs;

    @Column(name = "inputs_sha256", nullable = false, length = 64)
    private String inputsSha256;

    @Column(name = "adjustment_ref")
    private UUID adjustmentRef;

    @Column(name = "adjustment_request_id")
    private UUID adjustmentRequestId;

    @Column(name = "lease_generation")
    private Long leaseGeneration;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Override
    public UUID getId() {
        return id;
    }
}
