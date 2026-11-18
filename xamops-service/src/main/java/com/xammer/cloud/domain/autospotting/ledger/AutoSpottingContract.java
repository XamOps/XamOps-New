package com.xammer.cloud.domain.autospotting.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A client's AutoSpotting gain-share contract (design-ledger.md §2.3, PO-01, PO-06, PO-16). Terms are
 * immutable after insert: the only legal change is {@code effectiveTo}, to end the contract earlier
 * at a future instant. The database trigger enforces non-overlap per client, no backdating and the
 * immutability, whatever the application does. There is no delete.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "autospotting_contract")
public class AutoSpottingContract implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    /** Percent of net savings the client pays: {@code 0 < sharePct <= 100}. */
    @Column(name = "share_pct", nullable = false, precision = 9, scale = 6)
    private BigDecimal sharePct;

    /** Monthly cap in USD, or null for no cap. */
    @Column(name = "monthly_cap", precision = 19, scale = 2)
    private BigDecimal monthlyCap;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "cap_currency", nullable = false, length = 3, columnDefinition = "CHAR(3)")
    private String capCurrency = "USD";

    /** Null means "not declared", 0 means "declared none"; a missing value is never stored as 0. */
    @Column(name = "declared_discount_pct", precision = 9, scale = 6)
    private BigDecimal declaredDiscountPct;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Column(name = "effective_to")
    private Instant effectiveTo;

    @Column(name = "created_by", nullable = false, length = 160)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Transient
    private boolean inserted;

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return !inserted;
    }

    @PostLoad
    @PostPersist
    void markInserted() {
        this.inserted = true;
    }

    /** True when the contract is in force at {@code at}: {@code effectiveFrom <= at < effectiveTo}. */
    public boolean inForceAt(Instant at) {
        return !effectiveFrom.isAfter(at) && (effectiveTo == null || effectiveTo.isAfter(at));
    }
}
