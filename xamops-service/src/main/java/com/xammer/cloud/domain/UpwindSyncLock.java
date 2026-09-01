package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Lease row ensuring only one ASG instance runs a given Upwind sync at a time.
 * See V20260731_2__upwind_sync_lock.sql for why this exists.
 */
@Entity
@Data
@NoArgsConstructor
@Table(name = "upwind_sync_lock")
public class UpwindSyncLock {

    /** Lease name — the sync domain, e.g. DETECTIONS or FULL. */
    @Id
    private String name;

    /** Which instance currently holds it, for diagnosis. */
    private String holder;

    @Column(name = "acquired_at")
    private Instant acquiredAt;

    /** Lease expiry; a dead holder's lease ages out rather than blocking forever. */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
}
