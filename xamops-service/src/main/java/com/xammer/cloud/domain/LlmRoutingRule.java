package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "llm_routing_rule", indexes = {
    @Index(name = "idx_llm_routing_client", columnList = "client_id"),
    @Index(name = "idx_llm_routing_priority", columnList = "priority")
})
@Data
@NoArgsConstructor
public class LlmRoutingRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    @Column(name = "rule_name", nullable = false)
    private String ruleName;

    @Column(name = "source_model", nullable = false)
    private String sourceModel;

    @Column(name = "target_model", nullable = false)
    private String targetModel;

    @Column(name = "condition_type", nullable = false)
    private String conditionType;     // MAX_TOKENS, AGENT_NAME, REQUEST_PATH

    @Column(name = "condition_value", nullable = false)
    private String conditionValue;

    @Column(nullable = false)
    private Integer priority = 0;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() { createdAt = Instant.now(); updatedAt = Instant.now(); }

    @PreUpdate
    protected void onUpdate() { updatedAt = Instant.now(); }
}
