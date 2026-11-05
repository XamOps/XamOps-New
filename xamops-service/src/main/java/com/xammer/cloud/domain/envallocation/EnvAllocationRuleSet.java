package com.xammer.cloud.domain.envallocation;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A client's editable rule list, held as one JSON document.
 *
 * <p>The draft is edited as a whole — reordering is the normal edit — so storing it whole keeps a
 * save atomic. It never feeds a stored run directly: runs read a published
 * {@link EnvAllocationRuleVersion}, which is what keeps a past month explainable after the draft
 * moves on.
 */
@Entity
@Table(name = "env_allocation_rule_set",
        uniqueConstraints = @UniqueConstraint(name = "uk_env_allocation_rule_set_client",
                columnNames = {"client_id"}))
@Getter
@Setter
@NoArgsConstructor
public class EnvAllocationRuleSet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    @Column(name = "draft_json", nullable = false, columnDefinition = "TEXT")
    private String draftJson;

    @Column(name = "draft_updated_at")
    private LocalDateTime draftUpdatedAt;

    @Column(name = "draft_updated_by")
    private String draftUpdatedBy;

    /** 0 until the first publish. */
    @Column(name = "latest_version", nullable = false)
    private Integer latestVersion;
}
