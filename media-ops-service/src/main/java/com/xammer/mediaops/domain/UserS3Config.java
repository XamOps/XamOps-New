package com.xammer.mediaops.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Data
@NoArgsConstructor
@Table(name = "user_s3_configs")
public class UserS3Config {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "user_s3_config_seq")
    @SequenceGenerator(name = "user_s3_config_seq", sequenceName = "user_s3_configs_id_seq", allocationSize = 1)
    private Long id;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    @Column(nullable = false, length = 255)
    private String nickname;

    @Column(name = "bucket_name", nullable = false, length = 255)
    private String bucketName;

    @Column(nullable = false, length = 100)
    private String region;

    @Column(name = "access_key", columnDefinition = "TEXT")
    private String accessKey;

    @Column(name = "secret_key", columnDefinition = "TEXT")
    private String secretKey;

    @Column(name = "prefix_filter", length = 500)
    private String prefixFilter;

    @Column(name = "endpoint_url", columnDefinition = "TEXT")
    private String endpointUrl;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
