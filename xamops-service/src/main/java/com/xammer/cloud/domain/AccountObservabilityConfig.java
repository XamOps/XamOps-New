package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Data
@NoArgsConstructor
@Table(
    name = "account_observability_config",
    uniqueConstraints = @UniqueConstraint(columnNames = {"client_id", "cloud_account_id"})
)
public class AccountObservabilityConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "acc_obs_config_seq_gen")
    @SequenceGenerator(name = "acc_obs_config_seq_gen", sequenceName = "account_observability_config_id_seq", allocationSize = 1)
    private Long id;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    @Column(name = "cloud_account_id", nullable = false)
    private Long cloudAccountId;

    /** The provider-specific account identifier (AWS account ID, GCP project ID, Azure subscription ID) */
    @Column(name = "provider_account_id", nullable = false)
    private String providerAccountId;

    @Column(name = "account_name")
    private String accountName;

    @Column(name = "o2_org_id", nullable = false)
    private String o2OrgId;

    /** Base64-encoded credentials for collectors (Fluent Bit, OTel) to push data */
    @Column(name = "o2_ingestion_key", length = 512)
    private String o2IngestionKey;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
}
