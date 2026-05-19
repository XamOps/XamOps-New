package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

@Entity
@Table(name = "llm_provider_key")
@Data
@NoArgsConstructor
public class LlmProviderKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "key_group_id")
    private LlmKeyGroup keyGroup;

    @Column(length = 30)
    private String provider; // OPENAI, ANTHROPIC, GEMINI, BEDROCK

    @Column(name = "encrypted_api_key", columnDefinition = "TEXT")
    private String encryptedApiKey;

    @Column(name = "key_hint")
    private String keyHint;

    @Column
    private String status = "ACTIVE"; // ACTIVE, RATE_LIMITED, DISABLED

    @Column(name = "current_requests")
    private int currentRequests = 0;

    @Column(name = "rate_limited_until")
    private LocalDateTime rateLimitedUntil;

    @Column(name = "total_requests")
    private long totalRequests = 0L;

    @Column(name = "total_cost", precision = 14, scale = 6)
    private BigDecimal totalCost = BigDecimal.ZERO;

    @Column(name = "total_tokens")
    private long totalTokens = 0L;

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
