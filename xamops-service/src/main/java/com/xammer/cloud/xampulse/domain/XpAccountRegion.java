package com.xammer.cloud.xampulse.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Maps xp_account_region. Tenant key is cloudAccountId. */
@Entity
@Table(name = "xp_account_region")
@Getter
@Setter
@NoArgsConstructor
public class XpAccountRegion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cloud_account_id")
    private Long cloudAccountId;

    @Column(name = "region")
    private String region;

    @Column(name = "kind")
    private String kind;

    @Column(name = "stack_name")
    private String stackName;

    @Column(name = "stack_id")
    private String stackId;

    @Column(name = "template_version")
    private String templateVersion;

    @Column(name = "template_sha256")
    private String templateSha256;

    @Column(name = "state")
    private String state;

    @Column(name = "target_mode")
    private String targetMode;

    @Column(name = "target_instance_ids_json", columnDefinition = "text")
    private String targetInstanceIdsJson;

    @Column(name = "observe_tag_key")
    private String observeTagKey;

    @Column(name = "observe_tag_value")
    private String observeTagValue;

    @Column(name = "last_checked_at")
    private Instant lastCheckedAt;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

}
