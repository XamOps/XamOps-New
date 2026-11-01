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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * The plan record of design-core.md §7.3, table {@code spot_plan} (plan-phase-11.md task 11.1). A
 * plan is created OPEN with a 15-minute {@code expires_at} ({@code autospotting.plan.ttl}); confirm
 * moves it to CONFIRMED with a conditional {@code UPDATE ... WHERE status='OPEN' AND version=:v}
 * owned by {@code SpotPlanRepository}, so a stale entity can never confirm a plan twice (§7.4).
 *
 * <p>{@link #version} is a plain read-only column used by that guarded conditional UPDATE, not a JPA
 * {@code @Version}. JSONB columns ({@code body}, {@code warning_ids}, {@code confirm_acks}) are
 * stored as canonical JSON text via Hibernate's JSON type; timestamps are {@link Instant} (NFR-07).
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "spot_plan")
public class SpotPlan {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "group_id", nullable = false)
    private UUID groupId;

    @Column(name = "cloud_account_id", nullable = false)
    private Long cloudAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 24)
    private OperationKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PlanStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_state", nullable = false, length = 24)
    private GroupState fromState;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    @Column(name = "live_fingerprint", nullable = false, length = 64)
    private String liveFingerprint;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "body", nullable = false, columnDefinition = "jsonb")
    private String body;

    @Column(name = "requires_full_spot_optin", nullable = false)
    private boolean requiresFullSpotOptin;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "warning_ids", nullable = false, columnDefinition = "jsonb")
    private String warningIds;

    @Column(name = "dry_run", nullable = false)
    private boolean dryRun;

    @Column(name = "idempotency_key", length = 64)
    private String idempotencyKey;

    @Column(name = "request_sha256", length = 64)
    private String requestSha256;

    @Column(name = "created_by", nullable = false, length = 160)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "confirmed_by", length = 160)
    private String confirmedBy;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "confirm_acks", columnDefinition = "jsonb")
    private String confirmAcks;

    @Column(name = "confirm_reason", length = 500)
    private String confirmReason;

    @Column(name = "batch_id")
    private UUID batchId;

    /** The status set of a plan (design-core.md §7.3). Persisted as its name. */
    public enum PlanStatus {
        OPEN,
        CONFIRMED,
        EXPIRED,
        SUPERSEDED,
        STALE
    }
}
