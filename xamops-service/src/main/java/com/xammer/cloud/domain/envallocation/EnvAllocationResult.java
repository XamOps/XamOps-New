package com.xammer.cloud.domain.envallocation;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * One line of a run's output: how much of which bill lines went to which environment, and why.
 *
 * <p>The rule's id, name and position are copied here rather than joined, so the row reads the
 * same after the draft is edited. {@code percent} is the share of {@code sourceCost} this row
 * received — 100 for a whole assignment, the rule's split for a percentage rule, the computed
 * share for a proportional one.
 */
@Entity
@Table(name = "env_allocation_result",
        indexes = @Index(name = "idx_env_allocation_result_run", columnList = "run_id"))
@Getter
@Setter
@NoArgsConstructor
public class EnvAllocationResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "environment_name", nullable = false, length = 60)
    private String environmentName;

    @Column(name = "environment_color", length = 7)
    private String environmentColor;

    /** See {@code AllocationKind}. */
    @Column(name = "allocation_kind", nullable = false, length = 20)
    private String allocationKind;

    @Column(name = "rule_id", length = 40)
    private String ruleId;

    @Column(name = "rule_name", length = 120)
    private String ruleName;

    @Column(name = "rule_position")
    private Integer rulePosition;

    @Column(name = "service")
    private String service;

    @Column(name = "usage_type")
    private String usageType;

    @Column(name = "region", length = 64)
    private String region;

    @Column(name = "charge_type", length = 64)
    private String chargeType;

    /**
     * The CUR resource id these lines belong to, or null. Set only when the run read the month at
     * resource grain, which a label or resource-id rule causes; null on every row otherwise, and on
     * resource-less charges always. 512 to match {@code resource_label_assignments.resource_id},
     * since a CUR id can be a long ARN.
     */
    @Column(name = "resource_id", length = 512)
    private String resourceId;

    /** The tag value or label name a value-driven rule matched on, otherwise null. */
    @Column(name = "match_detail")
    private String matchDetail;

    @Column(name = "percent", precision = 9, scale = 4)
    private BigDecimal percent;

    @Column(name = "source_cost", nullable = false, precision = 20, scale = 6)
    private BigDecimal sourceCost;

    @Column(name = "amount", nullable = false, precision = 20, scale = 6)
    private BigDecimal amount;

    @Column(name = "line_count", nullable = false)
    private Integer lineCount;
}
