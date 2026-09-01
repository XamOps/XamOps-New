package com.xammer.cloud.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Data
@NoArgsConstructor
@Table(name = "observability_config")
public class ObservabilityConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "obs_config_seq_gen")
    @SequenceGenerator(name = "obs_config_seq_gen", sequenceName = "observability_config_id_seq", allocationSize = 1)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false)
    @JsonIgnore
    private Client client;

    @Column(name = "o2_org_id", nullable = false)
    private String o2OrgId;

    @Column(name = "o2_ingestion_key", length = 512)
    private String o2IngestionKey;  // given to collectors (write-only, scoped to org)

    @Column(name = "o2_api_key", length = 512)
    private String o2ApiKey;        // used by backend to proxy queries

    @Column(name = "retention_days")
    private int retentionDays = 30;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
