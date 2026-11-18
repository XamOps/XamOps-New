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
import java.time.LocalDate;
import java.util.UUID;

/** One provider-bill fact kept with the import that produced it (design-ledger.md §2.3). Append-only. */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "savings_bill_fact")
@IdClass(SavingsBillFactId.class)
public class SavingsBillFact extends AppendOnlyEntity<SavingsBillFactId> {

    @Id
    @Column(name = "import_id", nullable = false)
    private UUID importId;

    @Id
    @Column(name = "seq", nullable = false)
    private int seq;

    @Column(name = "usage_day", nullable = false)
    private LocalDate usageDay;

    @Column(name = "region", length = 64)
    private String region;

    @Column(name = "instance_type", length = 64)
    private String instanceType;

    @Column(name = "resource_id", length = 400)
    private String resourceId;

    @Column(name = "fact_kind", nullable = false, length = 24)
    private String factKind;

    @Column(name = "amount", nullable = false, precision = 24, scale = 10)
    private BigDecimal amount;

    @Column(name = "unit", nullable = false, length = 16)
    private String unit;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3, columnDefinition = "CHAR(3)")
    private String currency;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail", columnDefinition = "jsonb")
    private String detail;

    @Override
    public SavingsBillFactId getId() {
        return new SavingsBillFactId(importId, seq);
    }
}
