package com.xammer.cloud.domain.autospotting.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One line of a statement (design-ledger.md §2.3, §8.3). {@code rawNet} keeps the unclamped figure
 * (PO-26); {@code billableAmount} is rounded once, HALF_UP, to the currency's minor unit and the
 * database refuses a negative one on a group line and any currency but USD (PD-01). Append-only.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "savings_statement_line")
@IdClass(SavingsStatementLineId.class)
public class SavingsStatementLine extends AppendOnlyEntity<SavingsStatementLineId> {

    @Id
    @Column(name = "statement_id", nullable = false)
    private UUID statementId;

    @Id
    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(name = "line_kind", nullable = false, length = 12)
    private String lineKind;

    @Column(name = "group_id", nullable = false)
    private UUID groupId;

    @Column(name = "group_key", nullable = false, length = 300)
    private String groupKey;

    @Column(name = "tier", nullable = false, length = 20)
    private String tier;

    @Column(name = "billable", nullable = false)
    private boolean billable;

    @Column(name = "non_billable_reason", length = 48)
    private String nonBillableReason;

    @Column(name = "instance_hours", nullable = false, precision = 24, scale = 6)
    private BigDecimal instanceHours;

    @Column(name = "counterfactual_cost", nullable = false, precision = 24, scale = 10)
    private BigDecimal counterfactualCost;

    @Column(name = "spot_cost", nullable = false, precision = 24, scale = 10)
    private BigDecimal spotCost;

    @Column(name = "commitment_deduction", nullable = false, precision = 24, scale = 10)
    private BigDecimal commitmentDeduction;

    @Column(name = "raw_net", nullable = false, precision = 24, scale = 10)
    private BigDecimal rawNet;

    @Column(name = "billable_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal billableAmount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3, columnDefinition = "CHAR(3)")
    private String currency;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail", nullable = false, columnDefinition = "jsonb")
    private String detail;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "entry_ids", nullable = false, columnDefinition = "uuid[]")
    private UUID[] entryIds = new UUID[0];

    @Column(name = "adjustment_ref_line", length = 80)
    private String adjustmentRefLine;

    @Override
    public SavingsStatementLineId getId() {
        return new SavingsStatementLineId(statementId, lineNo);
    }
}
