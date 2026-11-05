package com.xammer.cloud.domain.envallocation;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * An immutable, published snapshot of a client's rules.
 *
 * <p>The environments are snapshotted alongside the rules, names and match values included. A run
 * made with version 3 must still say "R&amp;D" after someone renames that environment to
 * "Research", because that is what the auditor was given.
 */
@Entity
@Table(name = "env_allocation_rule_version",
        uniqueConstraints = @UniqueConstraint(name = "uk_env_allocation_rule_version",
                columnNames = {"client_id", "version_number"}))
@Getter
@Setter
@NoArgsConstructor
public class EnvAllocationRuleVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    @Column(name = "version_number", nullable = false)
    private Integer versionNumber;

    @Column(name = "rules_json", nullable = false, columnDefinition = "TEXT")
    private String rulesJson;

    @Column(name = "environments_json", nullable = false, columnDefinition = "TEXT")
    private String environmentsJson;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "published_at", nullable = false)
    private LocalDateTime publishedAt;

    @Column(name = "published_by")
    private String publishedBy;
}
