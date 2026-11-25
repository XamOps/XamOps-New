package com.xammer.cloud.xampulse.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Maps xp_audit. Tenant key is cloudAccountId. */
@Entity
@Table(name = "xp_audit")
@Getter
@Setter
@NoArgsConstructor
public class XpAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ts")
    private Instant ts;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "cloud_account_id")
    private Long cloudAccountId;

    @Column(name = "action")
    private String action;

    @Column(name = "target")
    private String target;

    @Column(name = "ip")
    private String ip;

    @Column(name = "detail_json", columnDefinition = "text")
    private String detailJson;

}
