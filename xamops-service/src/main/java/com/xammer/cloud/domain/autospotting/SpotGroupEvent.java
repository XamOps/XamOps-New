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

import java.time.Instant;
import java.util.UUID;

/**
 * An analytics event for either cloud (design-core.md §8.1, table {@code spot_group_event}):
 * interruptions, preemptions, launches and failures. {@code ux_sge_dedupe} keeps the same fact
 * idempotent whichever path records it. The {@code id} is a {@code BIGSERIAL} IDENTITY. Timestamps
 * are {@link Instant} (NFR-07).
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "spot_group_event")
public class SpotGroupEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 8)
    private Provider provider;

    @Column(name = "group_id", nullable = false)
    private UUID groupId;

    @Column(name = "event_type", nullable = false, length = 32)
    private String eventType;

    @Column(name = "event_key", nullable = false, length = 128)
    private String eventKey;

    @Column(name = "instance_id", length = 100)
    private String instanceId;

    @Column(name = "instance_type", length = 64)
    private String instanceType;

    @Column(name = "zone", length = 64)
    private String zone;

    @Column(name = "lifecycle", length = 16)
    private String lifecycle;

    @Column(name = "failure_class", length = 24)
    private String failureClass;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "source", nullable = false, length = 8)
    private String source;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;
}
