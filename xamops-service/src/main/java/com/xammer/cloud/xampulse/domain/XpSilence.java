package com.xammer.cloud.xampulse.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Maps xp_silence. Tenant key is cloudAccountId. */
@Entity
@Table(name = "xp_silence")
@Getter
@Setter
@NoArgsConstructor
public class XpSilence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cloud_account_id")
    private Long cloudAccountId;

    @Column(name = "matcher_json", columnDefinition = "text")
    private String matcherJson;

    @Column(name = "starts_at")
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "reason")
    private String reason;

}
