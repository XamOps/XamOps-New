package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * One Cost and Usage Report that XamOps can query, and everything needed to
 * query it.
 *
 * <p>Deliberately a separate table with a many-to-one from {@code cloud_account}
 * rather than columns on the account, because the mapping is genuinely
 * many-to-one: a single CUR in a XamOps management/payer account contains every
 * member account under that MPA. Storing bucket and prefix per account would
 * duplicate one row's worth of config across every account in the org and let
 * the copies drift.
 *
 * <p>The discovery fields ({@code timeUnit}, {@code format},
 * {@code resourceIdsEnabled}, {@code splitCostDataEnabled}) are read from the
 * report's {@code Manifest.json} rather than assumed. For
 * {@link AwsBillingSource#EXISTING_CUR} they describe a report XamOps did not
 * create and cannot change: a customer's existing CUR may well be monthly CSV
 * with no resource IDs, which supports far less than a report we provisioned.
 * Features gate on these instead of failing at query time.
 */
@Entity
@Data
@NoArgsConstructor
@Table(name = "cur_source")
public class CurSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Human label for the admin UI, e.g. "Xammer MPA (605134457560)". */
    @Column(nullable = false)
    private String name;

    /**
     * The AWS account that owns the S3 bucket. For {@code XAMMER_MPA_CUR} this
     * is a XamOps account and {@link #xamopsOwned} is true; otherwise it is the
     * customer account, reached by assuming that account's XamOps role.
     */
    @Column(name = "owner_account_id", nullable = false)
    private String ownerAccountId;

    /**
     * True when the bucket lives in a XamOps account rather than a customer's.
     *
     * <p>On its own this does <em>not</em> determine the credential — see
     * {@link #assumeRoleArn}. XamOps can operate more than one management/payer
     * account, and only the one the application runs in is reachable with the
     * application's own credentials.
     */
    @Column(name = "xamops_owned", nullable = false)
    private boolean xamopsOwned = false;

    /**
     * Role to assume to reach this report, or null to use the caller's default
     * credentials.
     *
     * <p>Three combinations, and the credential is explicit in all of them rather
     * than inferred from ownership:
     * <ul>
     *   <li>{@code xamopsOwned} + null — the MPA XamOps itself runs in; host credentials.</li>
     *   <li>{@code xamopsOwned} + set — another XamOps MPA, a different AWS account.</li>
     *   <li>not {@code xamopsOwned} — a customer account, reached by its onboarding role;
     *       this field is ignored.</li>
     * </ul>
     */
    @Column(name = "assume_role_arn", length = 512)
    private String assumeRoleArn;

    /** External ID for {@link #assumeRoleArn} when its trust policy requires one. */
    @Column(name = "assume_role_external_id")
    private String assumeRoleExternalId;

    @Column(name = "s3_bucket", nullable = false)
    private String s3Bucket;

    /** Key prefix the report is delivered under, no leading or trailing slash. */
    @Column(name = "s3_prefix")
    private String s3Prefix;

    /** Report name as given to CUR / Data Exports; also the manifest folder name. */
    @Column(name = "report_name", nullable = false)
    private String reportName;

    /** Region of the S3 bucket — where Athena must run, not where the report was defined. */
    @Column(name = "region", nullable = false)
    private String region;

    @Column(name = "athena_database")
    private String athenaDatabase;

    @Column(name = "athena_table")
    private String athenaTable;

    @Column(name = "athena_workgroup")
    private String athenaWorkgroup = "primary";

    /**
     * s3:// URI Athena writes query results to. Athena has no mode that avoids
     * writing results, so this is the one write a CUR-reading role needs; it is
     * scoped to its own prefix in the CloudFormation policy.
     */
    @Column(name = "athena_results_location")
    private String athenaResultsLocation;

    /** HOURLY, DAILY or MONTHLY, from the manifest. A MONTHLY report cannot serve daily charts. */
    @Column(name = "time_unit", length = 16)
    private String timeUnit;

    /** Parquet or csv/gzip, from the manifest. */
    @Column(name = "format", length = 16)
    private String format;

    /** CUR 1.0 ("cur") or Data Exports / CUR 2.0 ("cur2"); column naming differs between them. */
    @Column(name = "cur_version", length = 8)
    private String curVersion = "cur";

    /** True when lineItem/ResourceId is populated — gates every resource-level feature. */
    @Column(name = "resource_ids_enabled")
    private Boolean resourceIdsEnabled;

    /** True when splitLineItem/* columns exist — gates per-pod / per-task container cost. */
    @Column(name = "split_cost_data_enabled")
    private Boolean splitCostDataEnabled;

    /**
     * Earliest billing period present. A legacy CUR does not backfill, so this is
     * normally the month the report was created. Queries for ranges starting
     * before it must fall back to Cost Explorer rather than return a short sum.
     */
    @Column(name = "earliest_billing_period", length = 8)
    private String earliestBillingPeriod;

    /** PENDING, ACTIVE, or FAILED. Only ACTIVE sources are queried. */
    @Column(nullable = false, length = 16)
    private String status = "PENDING";

    /** Why validation failed, surfaced in the admin UI so a bad prefix is diagnosable. */
    @Column(name = "status_detail", length = 512)
    private String statusDetail;

    /** When the manifest was last read successfully. Staleness here means ingestion has stopped. */
    @Column(name = "last_validated_at")
    private LocalDateTime lastValidatedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /** True when this source is usable for queries right now. */
    public boolean isActive() {
        return "ACTIVE".equals(status);
    }

    /** True when the report carries per-day (or finer) buckets. */
    public boolean supportsDailyGranularity() {
        return "HOURLY".equalsIgnoreCase(timeUnit) || "DAILY".equalsIgnoreCase(timeUnit);
    }

    /** Fully-qualified Athena table, safe to interpolate into a query. */
    public String qualifiedTable() {
        return "\"" + athenaDatabase + "\".\"" + athenaTable + "\"";
    }
}
