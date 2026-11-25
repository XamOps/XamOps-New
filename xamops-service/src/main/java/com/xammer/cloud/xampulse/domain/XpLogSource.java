package com.xammer.cloud.xampulse.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Maps xp_log_source. Tenant key is cloudAccountId. */
@Entity
@Table(name = "xp_log_source")
@Getter
@Setter
@NoArgsConstructor
public class XpLogSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cloud_account_id")
    private Long cloudAccountId;

    @Column(name = "kind")
    private String kind;

    @Column(name = "paths_json", columnDefinition = "text")
    private String pathsJson;

    @Column(name = "parser")
    private String parser;

    @Column(name = "enabled")
    private boolean enabled;

    @Column(name = "redaction_json", columnDefinition = "text")
    private String redactionJson;

    @Column(name = "sampling_json", columnDefinition = "text")
    private String samplingJson;

}
