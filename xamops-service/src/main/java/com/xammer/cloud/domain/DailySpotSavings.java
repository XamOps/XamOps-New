package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.LocalDate;

/**
 * DailySpotSavings Entity
 *
 * Aggregated savings per account for fast dashboard reporting.
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

    public DailySpotSavings(CloudAccount account, LocalDate date) {
        this.account = account;
        this.date = date;
    }
}
