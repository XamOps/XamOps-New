package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;

import jakarta.persistence.*;
import java.time.LocalDate;

/**
 * DailySpotSavings Entity
 *
 * Aggregated savings per account for fast dashboard reporting.
 *
 * <p>Every row is legacy and never billable (FR-LED-20, D-12). {@code legacy}, {@code billable}
 * and {@code source} are set only by their column defaults
 * ({@code V20261009_1__autospotting_legacy_savings_flag.sql}); the application never writes them,
 * and the {@code ck_dss_legacy} check rejects a billable row.
 */
@Entity
@Data
@NoArgsConstructor
@Table(name = "daily_spot_savings", uniqueConstraints = {
        @UniqueConstraint(columnNames = { "account_id", "date" })
})
public class DailySpotSavings {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "daily_savings_seq_gen")
    @SequenceGenerator(name = "daily_savings_seq_gen", sequenceName = "daily_savings_id_seq", allocationSize = 1)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private CloudAccount account;

    @Column(nullable = false)
    private LocalDate date;

    @Column(name = "amount_saved", nullable = false)
    private Double amountSaved = 0.0;

    @Column(name = "swap_count")
    private Integer swapCount = 0;

    // Read-only: inserts and updates leave these to the database. The defaults are repeated
    // here so that a ddl-auto run that adds the columns before Flyway still marks rows legacy.
    @ColumnDefault("true")
    @Column(name = "legacy", nullable = false, insertable = false, updatable = false)
    private Boolean legacy;

    @ColumnDefault("false")
    @Column(name = "billable", nullable = false, insertable = false, updatable = false)
    private Boolean billable;

    @ColumnDefault("'LEGACY'")
    @Column(name = "source", length = 16, nullable = false, insertable = false, updatable = false)
    private String source;

    public DailySpotSavings(CloudAccount account, LocalDate date) {
        this.account = account;
        this.date = date;
    }
}
