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

import java.time.Instant;
import java.util.UUID;

/** One writer run for one account and UTC day; runs form a linear chain per window (design-ledger.md §2.2). */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "savings_ledger_run")
public class SavingsLedgerRun extends AppendOnlyEntity<UUID> {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 8)
    private Provider provider;

    @Column(name = "cloud_account_id", nullable = false)
    private Long cloudAccountId;

    @Column(name = "window_start", nullable = false)
    private Instant windowStart;

    @Column(name = "window_end", nullable = false)
    private Instant windowEnd;

    @Column(name = "calc_version", nullable = false, length = 16)
    private String calcVersion;

    @Column(name = "inputs_sha256", nullable = false, length = 64)
    private String inputsSha256;

    @Column(name = "supersedes_run_id")
    private UUID supersedesRunId;

    @Column(name = "trigger", nullable = false, length = 24)
    private String trigger;

    @Column(name = "job_lease_name", nullable = false, length = 64)
    private String jobLeaseName;

    @Column(name = "lease_generation", nullable = false)
    private long leaseGeneration;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Override
    public UUID getId() {
        return id;
    }
}
