package com.xammer.cloud.domain.autospotting;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * The lifecycle row of a managed group (design-core.md §2.4, table {@code spot_group}).
 *
 * <p>Mapped {@link Immutable} (plan task 3.2): JPA inserts the row once and never updates it, so a
 * stale entity can never write back the lease or runtime columns. Every column update is a guarded
 * native statement owned by {@code LeaseService}, {@code GroupLifecycleService} or the reconciler
 * (design-core.md §2.4 "Column ownership"), none of which arrives in Phase 03. {@link #version} is a
 * plain read-only column used by the guarded native lifecycle UPDATE (§2.3), <strong>not</strong> a
 * JPA {@code @Version}, so another actor's write to an unrelated column can't fail a transition.
 *
 * <p>Timestamps are {@link Instant} (NFR-07). JSONB columns are stored as their canonical JSON text
 * via Hibernate's JSON type.
 */
@Entity
@Immutable
@Getter
@Setter
@NoArgsConstructor
@Table(name = "spot_group")
public class SpotGroup {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 8)
    private Provider provider;

    @Column(name = "cloud_account_id", nullable = false)
    private Long cloudAccountId;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    @Column(name = "group_key", nullable = false, length = 300)
    private String groupKey;

    @Column(name = "provider_stable_id", length = 400)
    private String providerStableId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 24)
    private GroupState state;

    @Enumerated(EnumType.STRING)
    @Column(name = "pause_reason", length = 24)
    private PauseReason pauseReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "paused_from_state", length = 24)
    private GroupState pausedFromState;

    @Enumerated(EnumType.STRING)
    @Column(name = "drift_status", nullable = false, length = 16)
    private DriftStatus driftStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "drift_detail", columnDefinition = "jsonb")
    private String driftDetail;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "desired_config", columnDefinition = "jsonb")
    private String desiredConfig;

    @Column(name = "desired_fingerprint", length = 64)
    private String desiredFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(name = "config_mode", nullable = false, length = 12)
    private ConfigMode configMode;

    @Column(name = "fallback_fingerprint", length = 64)
    private String fallbackFingerprint;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "accepted_fields", columnDefinition = "jsonb")
    private String acceptedFields;

    @Column(name = "snapshot_id")
    private UUID snapshotId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "owned_artifacts", nullable = false, columnDefinition = "jsonb")
    private String ownedArtifacts;

    @Column(name = "current_operation_id")
    private UUID currentOperationId;

    @Column(name = "last_error_code", length = 64)
    private String lastErrorCode;

    @Column(name = "last_error_message", length = 1000)
    private String lastErrorMessage;

    @Column(name = "lease_holder", length = 160)
    private String leaseHolder;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "lease_generation", nullable = false)
    private long leaseGeneration;

    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "state_changed_at", nullable = false)
    private Instant stateChangedAt;

    @Column(name = "last_reconciled_at")
    private Instant lastReconciledAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "health_state", columnDefinition = "jsonb")
    private String healthState;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "last_capacity", columnDefinition = "jsonb")
    private String lastCapacity;

    @Column(name = "reconcile_hint_at")
    private Instant reconcileHintAt;

    @Column(name = "created_by", nullable = false, length = 160)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
