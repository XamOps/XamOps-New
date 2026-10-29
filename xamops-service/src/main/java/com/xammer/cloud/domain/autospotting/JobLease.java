package com.xammer.cloud.domain.autospotting;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A lease for one scheduled AutoSpotting job (design-core.md §3.2, table
 * {@code autospotting_job_lease}; names such as {@code reconciler:aws}, {@code discovery:gcp}). The
 * acquire/renew/release statements are guarded native UPDATEs that arrive with their owner
 * {@code LeaseService} in Phase 04. Timestamps are {@link Instant} (NFR-07).
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "autospotting_job_lease")
public class JobLease {

    @Id
    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Column(name = "holder", length = 160)
    private String holder;

    @Column(name = "generation", nullable = false)
    private long generation;

    @Column(name = "acquired_at")
    private Instant acquiredAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "last_run_key", length = 64)
    private String lastRunKey;

    @Column(name = "last_completed_at")
    private Instant lastCompletedAt;
}
