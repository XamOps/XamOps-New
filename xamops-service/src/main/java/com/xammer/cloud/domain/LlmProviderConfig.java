package com.xammer.cloud.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "llm_provider_config")
@Data
@NoArgsConstructor
public class LlmProviderConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false)
    @JsonIgnore
    private Client client;

    @Column(nullable = false)
    private String provider; // OPENAI, ANTHROPIC, GEMINI, BEDROCK

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "api_key_encrypted", columnDefinition = "TEXT")
    private String apiKeyEncrypted;

    @Column(name = "aws_region")
    private String awsRegion;

    @Column(name = "aws_access_key_encrypted", columnDefinition = "TEXT")
    private String awsAccessKeyEncrypted;

    @Column(name = "aws_secret_key_encrypted", columnDefinition = "TEXT")
    private String awsSecretKeyEncrypted;

    @Column(nullable = false)
    private String status = "ACTIVE";

    @Column(name = "proxy_endpoint_id", unique = true, nullable = false)
    private String proxyEndpointId;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "last_error_at")
    private Instant lastErrorAt;

    @Column(name = "last_error_message", columnDefinition = "TEXT")
    private String lastErrorMessage;

    @Column(name = "request_count")
    private Long requestCount = 0L;

    @Column(name = "error_count")
    private Long errorCount = 0L;

    @Column(name = "usage_limit")
    private Long usageLimit;

    @Column(name = "assigned_team")
    private String assignedTeam;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
