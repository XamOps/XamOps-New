package com.xammer.cloud.domain.autospotting;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

/**
 * One append-only state transition of a group (design-core.md §2.4, table
 * {@code spot_group_transition}). The ledger reads state intervals from these rows in {@code id}
 * order (ledger §6.2). Append-only: a {@code BEFORE UPDATE OR DELETE} trigger rejects any change,
 * and {@code trg_sgt_monotonic} rejects an inserted {@code effective_at} earlier than the group's
 * latest (DR-01). Mapped {@link Immutable}; the {@code id} is a {@code BIGSERIAL} IDENTITY.
 */
@Entity
@Immutable
@Getter
@Setter
@NoArgsConstructor
@Table(name = "spot_group_transition")
public class SpotGroupTransition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "group_id", nullable = false)
    private UUID groupId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_state", length = 24)
    private GroupState fromState;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_state", nullable = false, length = 24)
    private GroupState toState;

    @Column(name = "reason_code", nullable = false, length = 48)
    private String reasonCode;

    @Column(name = "actor_type", nullable = false, length = 12)
    private String actorType;

    @Column(name = "actor_id", length = 160)
    private String actorId;

    @Column(name = "operation_id")
    private UUID operationId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "effective_at", nullable = false)
    private Instant effectiveAt;
}
