package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.time.Instant;

/**
 * Per-account inventory sync bookkeeping.
 *
 * <p>
 * Kept out of {@code cached_data} on purpose: this is control state, not a cached
 * payload. The reconciler reads it to decide whether an account is due, the UI reads
 * it to render "last synced", and support reads {@link #mode} and {@link #lastError}
 * to answer "why is this account's cloud list stale" without trawling logs.
 *
 * <p>
 * {@link #indexType} matters more than it looks. Resource Explorer returns <em>partial</em>
 * results when no service-linked role exists in the customer account — tagged resources
 * plus untagged ones created after Oct 2025, but not full history. Recording that
 * distinction is what stops us presenting a half inventory as a complete one.
 */
@Entity
@Table(name = "aws_inventory_sync_state")
@Data
@NoArgsConstructor
public class AwsInventorySyncState {

    /** Resource Explorer answered and we trust it for this account. */
    public static final String MODE_RESOURCE_EXPLORER = "RESOURCE_EXPLORER";
    /** Resource Explorer unusable (SCP, unsupported region, empty) — using describes. */
    public static final String MODE_DESCRIBE_FALLBACK = "DESCRIBE_FALLBACK";
    public static final String MODE_UNKNOWN = "UNKNOWN";

    /** Aggregator index present: one query covers every region. */
    public static final String INDEX_AGGREGATOR = "AGGREGATOR";
    /** Local index only: per-region queries, still cheap but more calls. */
    public static final String INDEX_LOCAL = "LOCAL";
    /** No user-owned index: results are partial. */
    public static final String INDEX_PARTIAL = "PARTIAL";
    /**
     * Aggregator index present, but one or more enabled regions have no index — typically
     * AWS opt-in regions, which Resource Explorer does not index automatically. Resources
     * in those regions are invisible to the plane and must come from the describe path.
     */
    public static final String INDEX_AGGREGATOR_INCOMPLETE = "AGGREGATOR_INCOMPLETE";
    public static final String INDEX_NONE = "NONE";

    @Id
    @Column(name = "cloud_account_id")
    private Long cloudAccountId;

    @Column(name = "aws_account_id", nullable = false, length = 32)
    private String awsAccountId;

    @Column(name = "sync_generation", nullable = false)
    private Long syncGeneration = 0L;

    @Column(name = "last_full_sync_at")
    private Instant lastFullSyncAt;

    @Column(name = "last_event_at")
    private Instant lastEventAt;

    @Column(name = "mode", nullable = false, length = 32)
    private String mode = MODE_UNKNOWN;

    @Column(name = "index_type", length = 32)
    private String indexType;

    @Column(name = "aggregator_region", length = 64)
    private String aggregatorRegion;

    /**
     * Enabled regions with no Resource Explorer index, comma-separated.
     *
     * <p>
     * Resource Explorer does not auto-create indexes in AWS opt-in regions, so an account
     * that has opted into, say, ap-south-2 will have resources there that the aggregator
     * index cannot see. Measured on a real account: 18 enabled regions, 17 indexed, and the
     * 7 EC2 instances in the unindexed region were entirely absent from the search results
     * while {@code describeInstances} found them.
     *
     * <p>
     * Recorded because the consequence is silent under-reporting — the page looks fine and
     * simply omits resources. The reconciler also refuses to sweep rows in these regions,
     * so anything the describe fallback discovered there survives instead of being
     * tombstoned as "no longer present in AWS".
     */
    @Column(name = "uncovered_regions", length = 1024)
    private String uncoveredRegions;

    @Column(name = "resource_count", nullable = false)
    private Integer resourceCount = 0;

    @Column(name = "last_error", length = 1024)
    private String lastError;

    @Column(name = "last_sync_duration_ms")
    private Long lastSyncDurationMs;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
