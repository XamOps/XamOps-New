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
import java.util.UUID;

/** A price as the source stated it, kept forever (design-ledger.md §2.2, AC-LED-18). Append-only. */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "spot_price_snapshot")
public class SpotPriceSnapshot extends AppendOnlyEntity<UUID> {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 8)
    private Provider provider;

    @Column(name = "source", nullable = false, length = 24)
    private String source;

    @Column(name = "price_kind", nullable = false, length = 16)
    private String priceKind;

    @Column(name = "region", nullable = false, length = 64)
    private String region;

    @Column(name = "zone", length = 64)
    private String zone;

    @Column(name = "sku_or_type", nullable = false, length = 200)
    private String skuOrType;

    @Column(name = "platform", length = 32)
    private String platform;

    /** Per unit, exact as sourced, at least 10 decimal places (FR-LED-17). */
    @Column(name = "rate", nullable = false, precision = 24, scale = 12)
    private BigDecimal rate;

    @Column(name = "unit", nullable = false, length = 16)
    private String unit;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3, columnDefinition = "CHAR(3)")
    private String currency;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Column(name = "effective_to")
    private Instant effectiveTo;

    @Column(name = "source_version", nullable = false, length = 200)
    private String sourceVersion;

    @Column(name = "source_effective_time_stated", nullable = false)
    private boolean sourceEffectiveTimeStated;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    @Column(name = "payload_sha256", nullable = false, length = 64)
    private String payloadSha256;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Override
    public UUID getId() {
        return id;
    }
}
