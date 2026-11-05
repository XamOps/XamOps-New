package com.xammer.cloud.domain.envallocation;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One closed month of one AWS account, put through one published rule version.
 *
 * <p>Runs are never overwritten. Re-running a month with a newer version adds a run beside the old
 * one, so both figures stay visible. A locked run is the month's official figure and blocks new
 * runs for that month until it is unlocked.
 */
@Entity
@Table(name = "env_allocation_run",
        indexes = @Index(name = "idx_env_allocation_run_month",
                columnList = "client_id, aws_account_id, billing_period"))
@Getter
@Setter
@NoArgsConstructor
public class EnvAllocationRun {

    public static final String QUEUED = "QUEUED";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    /** The internal {@code cloud_account} row id. */
    @Column(name = "cloud_account_id", nullable = false)
    private Long cloudAccountId;

    @Column(name = "aws_account_id", nullable = false, length = 12)
    private String awsAccountId;

    /** {@code YYYY-MM}. */
    @Column(name = "billing_period", nullable = false, length = 7)
    private String billingPeriod;

    /** {@code unblended} or {@code amortized}, the wire form of {@code CostBasis}. */
    @Column(name = "cost_basis", nullable = false, length = 20)
    private String costBasis;

    @Column(name = "rule_version_id", nullable = false)
    private Long ruleVersionId;

    @Column(name = "version_number", nullable = false)
    private Integer versionNumber;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    @Column(name = "total_cost", precision = 20, scale = 6)
    private BigDecimal totalCost;

    @Column(name = "allocated_cost", precision = 20, scale = 6)
    private BigDecimal allocatedCost;

    @Column(name = "unallocated_cost", precision = 20, scale = 6)
    private BigDecimal unallocatedCost;

    @Column(name = "line_count")
    private Integer lineCount;

    @Column(name = "cur_source_id")
    private Long curSourceId;

    @Column(name = "queued_at", nullable = false)
    private LocalDateTime queuedAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "started_by")
    private String startedBy;

    @Column(name = "locked", nullable = false)
    private Boolean locked = false;

    @Column(name = "locked_at")
    private LocalDateTime lockedAt;

    @Column(name = "locked_by")
    private String lockedBy;

    public boolean isLocked() {
        return Boolean.TRUE.equals(locked);
    }
}
