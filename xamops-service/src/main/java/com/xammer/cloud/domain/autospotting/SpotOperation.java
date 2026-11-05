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
 * A write-ahead operation record (design-core.md §4.5, table {@code spot_operation}). The runner
 * owns its writes through guarded native statements that arrive in Phase 06/07; {@link #version} is
 * a plain column, not a JPA {@code @Version}. JSONB via Hibernate's JSON type; timestamps are
 * {@link Instant} (NFR-07).
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "spot_operation")
public class SpotOperation {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "group_id", nullable = false)
    private UUID groupId;

    @Column(name = "cloud_account_id", nullable = false)
    private Long cloudAccountId;

    @Column(name = "plan_id", unique = true)
    private UUID planId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 24)
    private OperationKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_class", nullable = false, length = 8)
    private ActionClass actionClass;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private OperationStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "phase", nullable = false, length = 12)
    private OperationPhase phase;

    @Enumerated(EnumType.STRING)
    @Column(name = "wait_reason", length = 24)
    private WaitReason waitReason;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "step_seq", nullable = false)
    private int stepSeq;

    @Column(name = "first_mutation_seq")
    private Integer firstMutationSeq;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "progress", columnDefinition = "jsonb")
    private String progress;

    @Enumerated(EnumType.STRING)
    @Column(name = "prior_state", nullable = false, length = 24)
    private GroupState priorState;

    @Enumerated(EnumType.STRING)
    @Column(name = "prior_pause_reason", length = 24)
    private PauseReason priorPauseReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "compensation_target", nullable = false, length = 16)
    private CompensationTarget compensationTarget;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "compensation_config", columnDefinition = "jsonb")
    private String compensationConfig;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "target_config", columnDefinition = "jsonb")
    private String targetConfig;

    @Column(name = "cancel_requested", nullable = false)
    private boolean cancelRequested;

    @Column(name = "cancel_reason", length = 32)
    private String cancelReason;

    @Column(name = "compensate_requested", nullable = false)
    private boolean compensateRequested;

    @Column(name = "compensate_reason", length = 48)
    private String compensateReason;

    @Column(name = "preempted_by_op")
    private UUID preemptedByOp;

    @Column(name = "idempotency_key", length = 64)
    private String idempotencyKey;

    @Column(name = "request_sha256", length = 64)
    private String requestSha256;

    @Column(name = "requested_by", nullable = false, length = 160)
    private String requestedBy;

    @Column(name = "dry_run", nullable = false)
    private boolean dryRun;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "deadline_at", nullable = false)
    private Instant deadlineAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "version", nullable = false)
    private long version;
}
