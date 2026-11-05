package com.xammer.cloud.domain.envallocation;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One environment a client splits its bill into — Prod, UAT, R&amp;D, or whatever they call it.
 *
 * <p>Client-owned and fully editable. Defaults are seeded on first use, and every one of them can
 * be renamed or deleted afterwards.
 *
 * <p>{@code matchValues} lists the tag or label values that mean this environment, so a rule
 * that reads the {@code Environment} tag can send {@code prod} and {@code production} to the
 * same place. The name itself always matches, case-insensitively.
 */
@Entity
@Table(name = "env_allocation_environment",
        uniqueConstraints = @UniqueConstraint(name = "uk_env_allocation_environment_name",
                columnNames = {"client_id", "name"}))
@Getter
@Setter
@NoArgsConstructor
public class EnvAllocationEnvironment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    @Column(name = "name", nullable = false, length = 60)
    private String name;

    @Column(name = "color", nullable = false, length = 7)
    private String color;

    /** Comma-separated. Null or blank means only the name matches. */
    @Column(name = "match_values", length = 500)
    private String matchValues;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
