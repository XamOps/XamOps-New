package com.xammer.cloud.domain.autospotting;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
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
 * An append-only audit record (design-core.md §8, table {@code autospotting_audit}). A
 * {@code BEFORE UPDATE OR DELETE} trigger rejects any change, and the repository exposes only
 * {@code save} and finders. Mapped {@link Immutable}; the {@code id} is a {@code BIGSERIAL}
 * IDENTITY. JSONB columns carry redacted documents via Hibernate's JSON type (FR-SEC-06).
 */
@Entity
@Immutable
@Getter
@Setter
@NoArgsConstructor
@Table(name = "autospotting_audit")
public class AutoSpottingAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "event_type", nullable = false, length = 32)
    private String eventType;

    @Column(name = "actor_type", nullable = false, length = 12)
    private String actorType;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "actor_role", length = 32)
    private String actorRole;

    @Column(name = "impersonator_user_id")
    private Long impersonatorUserId;

    @Column(name = "request_id", length = 64)
    private String requestId;

    @Column(name = "operation_id")
    private UUID operationId;

    @Column(name = "plan_id")
    private UUID planId;

    @Column(name = "step_seq")
    private Integer stepSeq;

    @Column(name = "provider", length = 8)
    private String provider;

    @Column(name = "client_id")
    private Long clientId;

    @Column(name = "cloud_account_id")
    private Long cloudAccountId;

    @Column(name = "group_id")
    private UUID groupId;

    @Column(name = "group_key", length = 300)
    private String groupKey;

    @Column(name = "action", nullable = false, length = 48)
    private String action;

    @Column(name = "outcome", nullable = false, length = 16)
    private String outcome;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "cloud_request_ids", columnDefinition = "jsonb")
    private String cloudRequestIds;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_state", columnDefinition = "jsonb")
    private String beforeState;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_state", columnDefinition = "jsonb")
    private String afterState;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details", columnDefinition = "jsonb")
    private String details;
}
