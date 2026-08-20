package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import java.time.Instant;

/**
 * One AWS resource, one row.
 *
 * <p>
 * This replaces the {@code CLOUD_LIST::{acct}} and {@code CLOUD_LIST_GROUPED::{acct}}
 * JSON blobs in {@code cached_data}. The blob shape is what forced every page to be
 * all-or-nothing: to reflect a single new instance you had to re-run the whole
 * ~1000-call account scan and rewrite both blobs. With a row per resource an event can
 * patch one record, and pagination/filtering happen in SQL instead of {@code subList()}
 * over a deserialised account.
 *
 * <p>
 * {@link #displayType} intentionally carries the same strings
 * {@code CloudListService} already emits ("EC2 Instance", "EBS Volume", "VPC"), because
 * consumers filter on them by equality — {@code XamOpsRightsizingService} on
 * {@code "EC2 Instance"}, {@code CloudMapService} on {@code "VPC"}. Changing the
 * vocabulary here would silently empty those pages, so the mapping is pinned in
 * {@code AwsResourceTypeMapper} and the raw {@link #service}/{@link #nativeType} are
 * kept alongside it so nothing is lost for types we have not mapped yet.
 */
@Entity
@Table(name = "aws_cloud_resource")
@Data
@NoArgsConstructor
public class AwsCloudResource {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DELETED = "DELETED";

    public static final String SOURCE_RESOURCE_EXPLORER = "RESOURCE_EXPLORER";
    public static final String SOURCE_EVENT = "EVENT";
    public static final String SOURCE_DESCRIBE_FALLBACK = "DESCRIBE_FALLBACK";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cloud_account_id", nullable = false)
    private Long cloudAccountId;

    /**
     * Denormalised from {@link CloudAccount}. Nearly every read path arrives holding
     * the AWS account id from the request URL rather than our internal row id, so
     * carrying it here avoids a join on the hottest query in the product.
     */
    @Column(name = "aws_account_id", nullable = false, length = 32)
    private String awsAccountId;

    @Column(name = "arn", nullable = false, length = 2048)
    private String arn;

    /** Short form ("i-abc123", bucket name) — what the existing DTO and UI links use. */
    @Column(name = "resource_id", nullable = false, length = 512)
    private String resourceId;

    @Column(name = "name", length = 512)
    private String name;

    /** Resource Explorer's service token, e.g. "ec2". */
    @Column(name = "service", nullable = false, length = 64)
    private String service;

    /** Resource Explorer's type token, e.g. "ec2:instance". */
    @Column(name = "native_type", length = 128)
    private String nativeType;

    /** UI-facing group label, e.g. "EC2 Instance". Must match CloudListService. */
    @Column(name = "display_type", nullable = false, length = 128)
    private String displayType;

    @Column(name = "region", length = 64)
    private String region;

    @Column(name = "state", length = 64)
    private String state;

    /**
     * JSON object of AWS tags.
     *
     * <p>
     * Deliberately {@code text}, not {@code jsonb}. Hibernate binds a Java String as
     * {@code varchar}, and Postgres refuses to implicitly cast that to {@code jsonb} —
     * every insert failed with "column is of type jsonb but expression is of type character
     * varying", so no resource was ever persisted. Storing JSON as text needs no extra type
     * mapping or dependency. Nothing queries inside these documents in SQL today; if that
     * changes, the right move is a proper JSONB user type rather than reverting the column
     * definition and reintroducing this failure.
     */
    @Column(name = "tags", columnDefinition = "text")
    private String tags;

    /** JSON object of type-specific attributes, mapped into {@code ResourceDto.details}. */
    @Column(name = "details", columnDefinition = "text")
    private String details;

    @Column(name = "status", nullable = false, length = 16)
    private String status = STATUS_ACTIVE;

    @Column(name = "launch_time")
    private Instant launchTime;

    /**
     * When XamOps first observed this resource. Not the AWS creation time — Resource
     * Explorer does not report one for most types. Good enough for "new today" badges
     * and honest about what it means.
     */
    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    /** Stamped by the reconciler; rows left at an older generation get swept. */
    @Column(name = "sync_generation", nullable = false)
    private Long syncGeneration = 0L;

    @Column(name = "source", nullable = false, length = 32)
    private String source = SOURCE_RESOURCE_EXPLORER;
}
