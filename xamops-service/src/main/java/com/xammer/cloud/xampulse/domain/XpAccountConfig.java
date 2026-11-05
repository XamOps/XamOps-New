package com.xammer.cloud.xampulse.domain;

import com.xammer.cloud.domain.converter.EncryptedStringConverter;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Maps xp_account_config. Tenant key is cloudAccountId. */
@Entity
@Table(name = "xp_account_config")
@Getter
@Setter
@NoArgsConstructor
public class XpAccountConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cloud_account_id")
    private Long cloudAccountId;

    @Column(name = "client_id")
    private Long clientId;

    @Column(name = "state")
    private String state;

    @Column(name = "bucket_mode")
    private String bucketMode;

    @Column(name = "bucket_name")
    private String bucketName;

    @Column(name = "bucket_region")
    private String bucketRegion;

    @Column(name = "kms_key_arn")
    private String kmsKeyArn;

    @Column(name = "home_region")
    private String homeRegion;

    @Column(name = "stack_name")
    private String stackName;

    @Column(name = "stack_id")
    private String stackId;

    @Column(name = "template_version")
    private String templateVersion;

    @Column(name = "template_sha256")
    private String templateSha256;

    @Column(name = "archive_role_arn")
    private String archiveRoleArn;

    @Column(name = "archive_external_id_enc", columnDefinition = "text")
    @Convert(converter = EncryptedStringConverter.class)
    private String archiveExternalIdEnc;

    @Column(name = "hot_days")
    private int hotDays;

    @Column(name = "ia_after_days")
    private int iaAfterDays;

    @Column(name = "glacier_ir_after_days")
    private int glacierIrAfterDays;

    @Column(name = "expire_after_days")
    private int expireAfterDays;

    @Column(name = "ingest_quota_gb_day")
    private BigDecimal ingestQuotaGbDay;

    @Column(name = "llm_enabled")
    private boolean llmEnabled;

    @Column(name = "settings_json", columnDefinition = "text")
    private String settingsJson;

    /** Everything before this instant (hour aligned) is in the archive. Null until the first archive run. */
    @Column(name = "archive_watermark")
    private Instant archiveWatermark;

    @Column(name = "archive_failures")
    private int archiveFailures;

    @Column(name = "archive_last_error")
    private String archiveLastError;

    /** Days added to hot retention by extendHold (cap 7) and when it last ran. */
    @Column(name = "hold_extended_days")
    private int holdExtendedDays;

    @Column(name = "hold_extended_at")
    private Instant holdExtendedAt;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Version
    private long version;

}
