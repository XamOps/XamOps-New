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
 * A safety switch (design-core.md §6.1, table {@code autospotting_switch}), keyed by its scope
 * (e.g. {@code GLOBAL}, {@code CLOUD:aws}, {@code ACCOUNT:123}, {@code FREEZE}). Writes go through
 * the operator API ({@code SwitchService}) in Phase 05; {@link #version} is a plain column.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "autospotting_switch")
public class AutoSpottingSwitch {

    @Id
    @Column(name = "scope_key", nullable = false, length = 96)
    private String scopeKey;

    @Column(name = "engaged", nullable = false)
    private boolean engaged;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Column(name = "changed_by", nullable = false, length = 160)
    private String changedBy;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    @Column(name = "version", nullable = false)
    private long version;
}
