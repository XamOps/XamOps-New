package com.xammer.billops.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.Subselect;
import org.hibernate.annotations.Synchronize;

import java.time.YearMonth;

/**
 * Read-only view of the {@code cur_source} row that xamops-service maintains.
 *
 * <p>BillOps and xamops-service share one database and no code. xamops-service owns
 * this table end to end — it registers the report during onboarding, discovers the
 * Athena table from the manifest, and revalidates it nightly — so everything here is
 * {@link Immutable}: BillOps reads where to query and whether the report is healthy,
 * and never writes a word of it. A second writer would be the kind of bug that only
 * shows up as a customer's invoice quietly querying a table that was renamed.
 *
 * <p>Only the columns an invoice needs are mapped. The full entity lives in
 * xamops-service as {@code com.xammer.cloud.domain.CurSource}; duplicating all
 * twenty-odd columns here would create a second definition to keep in step for no
 * gain.
 *
 * <h2>Why a subselect and not {@code @Table}</h2>
 * billops runs {@code ddl-auto=update}. Mapped with {@code @Table}, Hibernate would
 * consider this table its own to create, and on any database where billops started
 * before xamops-service had run {@code V20260902_1__aws_cur_source.sql} it would create
 * {@code cur_source} with only these columns. That migration's {@code CREATE TABLE}
 * would then fail, and xamops-service would refuse to start. A subselect entity is
 * never part of schema generation, so billops can only ever read the table.
 */
@Entity
@Immutable
@Subselect("SELECT id, owner_account_id, athena_database, athena_table, athena_workgroup,"
        + " athena_results_location, region, cur_version, format, resource_ids_enabled,"
        + " earliest_billing_period, status, status_detail FROM cur_source")
@Synchronize("cur_source")
@NoArgsConstructor
public class CurSourceView {

    @Id
    private Long id;

    @Column(name = "owner_account_id", nullable = false)
    private String ownerAccountId;

    @Column(name = "athena_database")
    private String athenaDatabase;

    @Column(name = "athena_table")
    private String athenaTable;

    @Column(name = "athena_workgroup")
    private String athenaWorkgroup;

    @Column(name = "athena_results_location")
    private String athenaResultsLocation;

    @Column(name = "region")
    private String region;

    /** "cur" for CUR 1.0, "cur2" for Data Exports. Column naming differs between them. */
    @Column(name = "cur_version")
    private String curVersion;

    /** Parquet or csv/gzip. A CSV table is all-string, so every numeric needs a cast. */
    @Column(name = "format")
    private String format;

    /** True when line_item_resource_id is populated — without it there are no per-resource lines. */
    @Column(name = "resource_ids_enabled")
    private Boolean resourceIdsEnabled;

    /**
     * Earliest month present, "YYYY-MM". A CUR does not backfill, so a request for an
     * earlier month is not a failure to retry — that data will never exist.
     */
    @Column(name = "earliest_billing_period")
    private String earliestBillingPeriod;

    /** PENDING, ACTIVE or FAILED. Only ACTIVE is queryable. */
    @Column(name = "status", nullable = false)
    private String status;

    /** Why validation failed, so the admin sees the actual cause rather than "unavailable". */
    @Column(name = "status_detail")
    private String statusDetail;

    public Long getId() { return id; }
    public String getOwnerAccountId() { return ownerAccountId; }
    public String getAthenaDatabase() { return athenaDatabase; }
    public String getAthenaTable() { return athenaTable; }
    public String getAthenaWorkgroup() { return athenaWorkgroup; }
    public String getAthenaResultsLocation() { return athenaResultsLocation; }
    public String getRegion() { return region; }
    public String getCurVersion() { return curVersion; }
    public String getFormat() { return format; }
    public Boolean getResourceIdsEnabled() { return resourceIdsEnabled; }
    public String getEarliestBillingPeriod() { return earliestBillingPeriod; }
    public String getStatus() { return status; }
    public String getStatusDetail() { return statusDetail; }

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }

    /** True for CUR 2.0, whose column names and partitioning differ from CUR 1.0. */
    public boolean isCur2() {
        return "cur2".equalsIgnoreCase(curVersion);
    }

    /** True when the report is delivered as CSV rather than Parquet. */
    public boolean isCsv() {
        return format != null && format.toLowerCase().startsWith("csv");
    }

    /** {@code database.table}, as Athena needs it when the query names the table explicitly. */
    public String qualifiedTable() {
        return athenaDatabase + "." + athenaTable;
    }

    /**
     * The first month this report can answer for, or null when unknown.
     *
     * <p>Unknown is deliberately not treated as "nothing available": a source that
     * validated before this column was populated should still be queried, and an empty
     * result will say so honestly.
     */
    public YearMonth earliestMonth() {
        if (earliestBillingPeriod == null || earliestBillingPeriod.isBlank()) {
            return null;
        }
        try {
            return YearMonth.parse(earliestBillingPeriod.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
