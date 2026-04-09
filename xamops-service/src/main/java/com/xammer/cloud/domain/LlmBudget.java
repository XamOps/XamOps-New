package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "llm_budget", indexes = {
    @Index(name = "idx_llm_budget_client", columnList = "client_id"),
    @Index(name = "idx_llm_budget_provider_config", columnList = "provider_config_id")
})
@Data
@NoArgsConstructor
public class LlmBudget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    @Column(name = "provider_config_id")
    private Long providerConfigId;

    @Column(name = "budget_name", nullable = false)
    private String budgetName;

    @Column(name = "monthly_limit_usd", nullable = false)
    private Double monthlyLimitUsd;

    @Column(name = "alert_thresholds", nullable = false)
    private String alertThresholds = "50,80,100";

    @Column(name = "auto_disable", nullable = false)
    private Boolean autoDisable = false;

    @Column(nullable = false)
    private String status = "ACTIVE";

    @Column(name = "notification_email")
    private String notificationEmail;

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
