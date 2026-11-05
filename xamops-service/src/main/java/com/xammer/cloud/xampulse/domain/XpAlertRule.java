package com.xammer.cloud.xampulse.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Maps xp_alert_rule. Tenant key is cloudAccountId. */
@Entity
@Table(name = "xp_alert_rule")
@Getter
@Setter
@NoArgsConstructor
public class XpAlertRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cloud_account_id")
    private Long cloudAccountId;

    @Column(name = "name")
    private String name;

    @Column(name = "severity")
    private String severity;

    @Column(name = "enabled")
    private boolean enabled;

    @Column(name = "definition_json", columnDefinition = "text")
    private String definitionJson;

    @Version
    private long version;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

}
