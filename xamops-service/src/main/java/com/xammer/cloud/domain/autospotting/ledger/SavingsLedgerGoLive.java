package com.xammer.cloud.domain.autospotting.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * The go-live instant of one provider (design-ledger.md §2.3, FR-LED-21): no billable entry covers
 * usage before it. Set once and immutable. Keyed by the provider name ({@code AWS} or {@code GCP}).
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "savings_ledger_golive")
public class SavingsLedgerGoLive extends AppendOnlyEntity<String> {

    @Id
    @Column(name = "provider", nullable = false, length = 8)
    private String provider;

    @Column(name = "go_live_at", nullable = false)
    private Instant goLiveAt;

    @Column(name = "set_by", nullable = false, length = 160)
    private String setBy;

    @Column(name = "set_at", nullable = false)
    private Instant setAt;

    @Override
    public String getId() {
        return provider;
    }
}
