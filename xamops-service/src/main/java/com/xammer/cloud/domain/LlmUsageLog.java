package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "llm_usage_log", indexes = {
    @Index(name = "idx_llm_usage_client_ts", columnList = "client_id, timestamp"),
    @Index(name = "idx_llm_usage_provider", columnList = "provider"),
    @Index(name = "idx_llm_usage_model", columnList = "model"),
    @Index(name = "idx_llm_usage_agent", columnList = "agent_name")
})
@Data
@NoArgsConstructor
public class LlmUsageLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    @Column(name = "provider_config_id")
    private Long providerConfigId;

    @Column(nullable = false)
    private String provider;

    @Column(nullable = false)
    private String model;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Column(name = "total_tokens")
    private Integer totalTokens;

    @Column(name = "estimated_cost")
    private Double estimatedCost;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "status_code")
    private Integer statusCode;

    @Column(name = "is_error")
    private Boolean isError = false;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "request_path")
    private String requestPath;

    @Column(name = "agent_name")
    private String agentName;

    @Column(name = "routed_from")
    private String routedFrom;

    @Column(nullable = false)
    private Instant timestamp;

    @PrePersist
    protected void onCreate() {
        if (timestamp == null) timestamp = Instant.now();
    }
}
