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
 * An immutable capture of a group's original provider configuration (design-core.md §2.4, table
 * {@code spot_group_snapshot}). Written once; a {@code BEFORE UPDATE OR DELETE} trigger rejects any
 * change. Mapped {@link Immutable} so JPA never updates it. JSONB via Hibernate's JSON type.
 */
@Entity
@Immutable
@Getter
@Setter
@NoArgsConstructor
@Table(name = "spot_group_snapshot")
public class SpotGroupSnapshot {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "group_id", nullable = false)
    private UUID groupId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 8)
    private Provider provider;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    @Column(name = "exact", nullable = false)
    private boolean exact;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "payload_sha256", nullable = false, length = 64)
    private String payloadSha256;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    @Column(name = "captured_by_op")
    private UUID capturedByOp;
}
