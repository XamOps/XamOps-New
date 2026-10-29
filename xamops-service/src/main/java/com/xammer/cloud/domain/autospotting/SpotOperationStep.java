package com.xammer.cloud.domain.autospotting;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * One step of a Spot operation (design-core.md §4.5, table {@code spot_operation_step}), keyed by
 * {@code (operation_id, seq)} via {@link SpotOperationStepId}. Written by the runner through the
 * fenced step protocol in Phase 06/07. JSONB via Hibernate's JSON type; timestamps {@link Instant}.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@IdClass(SpotOperationStepId.class)
@Table(name = "spot_operation_step")
public class SpotOperationStep {

    @Id
    @Column(name = "operation_id", nullable = false)
    private UUID operationId;

    @Id
    @Column(name = "seq", nullable = false)
    private int seq;

    @Column(name = "name", nullable = false, length = 48)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private StepStatus status;

    @Column(name = "idempotency_token", nullable = false, length = 64)
    private String idempotencyToken;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "cloud_request_ids", columnDefinition = "jsonb")
    private String cloudRequestIds;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input", columnDefinition = "jsonb")
    private String input;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output", columnDefinition = "jsonb")
    private String output;

    @Column(name = "lease_generation", nullable = false)
    private long leaseGeneration;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "intent_at", nullable = false)
    private Instant intentAt;

    @Column(name = "completed_at")
    private Instant completedAt;
}
